package org.opensearch.migrations.replay.datahandlers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import javax.net.ssl.SSLEngine;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Log4j2
public class NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {

    DiagnosticTrackableCompletableFuture<String, Channel> fullyInitializedChannelFuture;
    AggregatedRawResponse.Builder responseBuilder;
    BacksideHttpWatcherHandler responseWatchHandler;
    final String diagnosticLabel;
    SSLEngine sslEngine;

    private static class SslErrorHandler extends ChannelDuplexHandler {

        private final Consumer<Throwable> onExceptionCaughtConsumer;

        private SslErrorHandler(Consumer<Throwable> onExceptionCaughtConsumer) {
            this.onExceptionCaughtConsumer = onExceptionCaughtConsumer;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            onExceptionCaughtConsumer.accept(cause);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            Throwable foundIssue;
            if (evt instanceof SslHandshakeCompletionEvent) {
                var sslHandshakeCompletion = (SslHandshakeCompletionEvent) evt;
                foundIssue = sslHandshakeCompletion.isSuccess() ? null : sslHandshakeCompletion.cause();
            } else if (evt instanceof SslCloseCompletionEvent) {
                var sslCloseCompletionEvent = (SslCloseCompletionEvent) evt;
                foundIssue = sslCloseCompletionEvent.isSuccess() ? null : sslCloseCompletionEvent.cause();
            } else {
                foundIssue = null;
            }

            if (foundIssue == null) {
                log.trace("User Event=" + evt);
                super.userEventTriggered(ctx, evt);
            } else {
                log.warn("exception event in ssl handshake (" + evt +
                        ") - triggering callback and eating the event", foundIssue);
                onExceptionCaughtConsumer.accept(foundIssue);
            }
        }
    }

    public NettyPacketToHttpConsumer(NioEventLoopGroup eventLoopGroup, URI serverUri, SslContext sslContext,
                                     String diagnosticLabel) {
        this.diagnosticLabel = "[" + diagnosticLabel + "] ";
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelDuplexHandler())
                .option(ChannelOption.AUTO_READ, false);
        String host = serverUri.getHost();
        int port = serverUri.getPort();
        log.debug("Active - setting up backend connection to " + host + ":" + port);
        var outboundChannelFuture = b.connect(host, port);
        //outboundChannel = outboundChannelFuture.channel();
        responseWatchHandler = new BacksideHttpWatcherHandler(responseBuilder);
        fullyInitializedChannelFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<>(), () -> "sslHandshakeFuture");

        outboundChannelFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // connection complete start to read first data
                log.debug(diagnosticLabel + "Done setting up backend channel & it was successful");
                var pipeline = future.channel().pipeline();
                pipeline.addFirst(new LoggingHandler("PRE_EVERYTHING", LogLevel.TRACE));
                if (sslContext != null) {
                    sslEngine = sslContext.newEngine(future.channel().alloc());
                    sslEngine.setUseClientMode(true);
                    var sslHandler = new SslHandler(sslEngine);
                    pipeline.addLast("ssl", sslHandler);
                    pipeline.addLast(new LoggingHandler("POST_SSL", LogLevel.TRACE));
                    pipeline.addLast(new SslErrorHandler(e-> terminateChannel(pipeline)));
                    pipeline.addLast(new LoggingHandler("POST_ERROR_CATCHER", LogLevel.TRACE));
                    sslHandler.handshakeFuture().addListener(f -> {
                        if (f.isSuccess()) {
                            addHttpTransactionHandlersToPipeline(pipeline);
                            fullyInitializedChannelFuture.future.complete(future.channel());
                        } else {
                            fullyInitializedChannelFuture.future.completeExceptionally(future.cause());
                        }
                    });
                } else {
                    fullyInitializedChannelFuture.future.complete(future.channel());
                    addHttpTransactionHandlersToPipeline(pipeline);
                }
            } else {
                // Close the connection if the connection attempt has failed.
                log.warn(diagnosticLabel + " CONNECT future was not successful, so setting the channel future's " +
                        "result to an exception");
                log.warn(future.cause());
                fullyInitializedChannelFuture.future.completeExceptionally(future.cause());
            }
        });
    }

    private void addHttpTransactionHandlersToPipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new BacksideSnifferHandler(responseBuilder));
        pipeline.addLast(new HttpResponseDecoder());
        // TODO - switch this out to use less memory.
        // We only need to know when the response has been fully received, not the contents
        // since we're already logging those in the sniffer earlier in the pipeline.
        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
        pipeline.addLast(responseWatchHandler);
        pipeline.addLast(new LoggingHandler("POST_EVERYTHING", LogLevel.TRACE));
    }

    private static void terminateChannel(ChannelPipeline pipeline) {
        while (pipeline.last() != null) {
            pipeline.removeLast();
        }
        pipeline.channel().close();
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String,Void> consumeBytes(ByteBuf packetData) {
        responseBuilder.addRequestPacket(packetData.duplicate());
        log.debug("Scheduling write of packetData["+packetData+"]" +
                " hash=" + System.identityHashCode(packetData));
        final var completableFuture = new DiagnosticTrackableCompletableFuture<String, Void>(new CompletableFuture<>(),
                ()->"CompletableFuture that will wait for the netty future to fill in the completion value");
//                writePacketAndUpdateFuture(packetData, completableFuture, channel);
        return fullyInitializedChannelFuture.getDeferredFutureThroughHandle((channel, channelInitException) -> {
            if (channelInitException == null) {
                log.trace("outboundChannelFuture has finished - retriggering consumeBytes" +
                        " hash=" + System.identityHashCode(packetData));
                writePacketAndUpdateFuture(packetData, completableFuture, channel);
            } else {
                log.warn(diagnosticLabel + "outbound channel was not set up successfully, NOT writing bytes " +
                        " hash=" + System.identityHashCode(packetData));
                completableFuture.future.completeExceptionally(channelInitException);
            }
            return completableFuture;
        }, ()->"consumeBytes - after channel is fully initialized (potentially waiting on TLS handshake)");
    }

    private void writePacketAndUpdateFuture(ByteBuf packetData,
                                            DiagnosticTrackableCompletableFuture<String, Void> completableFuture,
                                            Channel channel) {
        channel.writeAndFlush(packetData)
                .addListener((ChannelFutureListener) future -> {
                    Throwable cause = null;
                    try {
                        if (!future.isSuccess()) {
                            log.warn(diagnosticLabel + "closing outbound channel because WRITE future was not successful " +
                                    future.cause() + " hash=" + System.identityHashCode(packetData) +
                                    " will be sending the exception to " + completableFuture);
                            future.channel().close(); // close the backside
                            cause = future.cause();
                        }
                    } catch (Exception e) {
                        cause = e;
                    }
                    if (cause == null) {
                        log.debug("Signaling previously returned CompletableFuture packet write was successful: "
                                + packetData + " hash=" + System.identityHashCode(packetData));
                        completableFuture.future.complete(null);
                    } else {
                        log.trace("Signaling previously returned CompletableFuture packet write had an exception : "
                                + packetData + " hash=" + System.identityHashCode(packetData));
                        completableFuture.future.completeExceptionally(cause);
                    }
                });
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>
    finalizeRequest() {
        var future = new CompletableFuture();
        responseWatchHandler.addCallback(future::complete);
        return new DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>(future,
                ()->"NettyPacketToHttpConsumer.finalizeRequest()");
    }
}
