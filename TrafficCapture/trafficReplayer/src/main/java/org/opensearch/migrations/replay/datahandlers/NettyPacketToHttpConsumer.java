package org.opensearch.migrations.replay.datahandlers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

@Log4j2
public class NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {

    public static final String BACKSIDE_HTTP_WATCHER_HANDLER_NAME = "BACKSIDE_HTTP_WATCHER_HANDLER";
    /**
     * This is a future that chains work onto the channel.  If the value is ready, the future isn't waiting
     * on anything to happen for the channel.  If the future isn't done, something in the chain is still
     * pending.
     */
    DiagnosticTrackableCompletableFuture<String,Void> activeChannelFuture;
    private final Channel channel;
    AggregatedRawResponse.Builder responseBuilder;
    final String diagnosticLabel;

    public NettyPacketToHttpConsumer(NioEventLoopGroup eventLoopGroup, URI serverUri, SslContext sslContext,
                                     String diagnosticLabel) {
        this(createClientConnection(eventLoopGroup, sslContext, serverUri, diagnosticLabel), diagnosticLabel);
    }

    public NettyPacketToHttpConsumer(ChannelFuture clientConnection, String diagnosticLabel) {
        this.diagnosticLabel = "[" + diagnosticLabel + "] ";
        // Start the connection attempt.
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        DiagnosticTrackableCompletableFuture<String,Void>  initialFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<>(), () -> "sslHandshakeFuture");
        this.activeChannelFuture = initialFuture;
        this.channel = clientConnection.channel();

        log.debug("Incoming clientConnection has pipeline="+clientConnection.channel().pipeline());
        clientConnection.addListener(connectFuture -> {
            if (connectFuture.isSuccess()) {
                addHttpTransactionHandlersToPipeline(clientConnection);
            }
        }).addListener(completelySetupFuture -> {
            if (completelySetupFuture.isSuccess()) {
                initialFuture.future.complete(null);
            } else {
                initialFuture.future.completeExceptionally(completelySetupFuture.cause());
            }
        });
    }

    public static ChannelFuture createClientConnection(NioEventLoopGroup eventLoopGroup, SslContext sslContext,
                                                       URI serverUri, String diagnosticLabel) {
        String host = serverUri.getHost();
        int port = serverUri.getPort();
        log.debug("Active - setting up backend connection to " + host + ":" + port);

        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelDuplexHandler())
                .option(ChannelOption.AUTO_READ, false);

        var outboundChannelFuture = b.connect(host, port);

        var rval = new DefaultChannelPromise(outboundChannelFuture.channel());
        outboundChannelFuture.addListener((ChannelFutureListener) connectFuture -> {
            if (connectFuture.isSuccess()) {
                log.debug(diagnosticLabel + "Done setting up client channel & it was successful");
                if (sslContext != null) {
                    var pipeline = connectFuture.channel().pipeline();
                    var sslEngine = sslContext.newEngine(connectFuture.channel().alloc());
                    sslEngine.setUseClientMode(true);
                    var sslHandler = new SslHandler(sslEngine);
                    pipeline.addFirst("ssl", sslHandler);
                    pipeline.addFirst("PRE-SSL", new LoggingHandler(LogLevel.WARN));
                    sslHandler.handshakeFuture().addListener(handshakeFuture -> {
                        if (handshakeFuture.isSuccess()) {
                            rval.setSuccess();
                        } else {
                            rval.setFailure(handshakeFuture.cause());
                        }
                    });
                } else {
                    rval.setSuccess();
                }
            } else {
                // Close the connection if the connection attempt has failed.
                log.warn(diagnosticLabel + " CONNECT future was not successful, so setting the channel future's " +
                        "result to an exception");
                log.warn(connectFuture.cause());
                rval.setFailure(connectFuture.cause());
            }
        });
        return rval;
    }

    private void addHttpTransactionHandlersToPipeline(ChannelFuture channelFuture) {
        var pipeline = channelFuture.channel().pipeline();
        pipeline.addLast(new LoggingHandler("PRE_SNIFFERS", LogLevel.TRACE));
        pipeline.addLast(new BacksideSnifferHandler(responseBuilder));
        pipeline.addLast(new HttpResponseDecoder());
        // TODO - switch this out to use less memory.
        // We only need to know when the response has been fully received, not the contents
        // since we're already logging those in the sniffer earlier in the pipeline.
        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
        pipeline.addLast(new LoggingHandler("POST_HTTP_AGGREGATOR", LogLevel.TRACE));
        pipeline.addLast(BACKSIDE_HTTP_WATCHER_HANDLER_NAME, new BacksideHttpWatcherHandler(responseBuilder));
        pipeline.addLast(new LoggingHandler("POST_EVERYTHING", LogLevel.TRACE));
        log.debug("Added handlers to the pipeline: " + pipeline);
    }

    private void resetPipeline() {
        var pipeline = channel.pipeline();
        log.debug("Resetting the pipeline currently at: " + pipeline);
        while (!(pipeline.last() instanceof SslHandler) && (pipeline.last() != null)) {
            pipeline.removeLast();
        }
        log.debug("Reset the pipeline back to: " + pipeline);
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String,Void> consumeBytes(ByteBuf packetData) {
        log.debug("Scheduling write of packetData["+packetData+"]" +
                " hash=" + System.identityHashCode(packetData));
        activeChannelFuture = activeChannelFuture.getDeferredFutureThroughHandle((v, channelInitException) -> {
            if (channelInitException == null) {
                log.trace("outboundChannelFuture has finished - retriggering consumeBytes" +
                        " hash=" + System.identityHashCode(packetData));
                return writePacketAndUpdateFuture(packetData);
            } else {
                log.warn(diagnosticLabel + "outbound channel was not set up successfully, NOT writing bytes " +
                        " hash=" + System.identityHashCode(packetData));
                channel.close();
                return StringTrackableCompletableFuture.factory.failedFuture(channelInitException, ()->"");
            }
        }, ()->"consumeBytes - after channel is fully initialized (potentially waiting on TLS handshake)");
        return activeChannelFuture;
    }

    private DiagnosticTrackableCompletableFuture<String, Void>
    writePacketAndUpdateFuture(ByteBuf packetData) {
        final var completableFuture = new DiagnosticTrackableCompletableFuture<String, Void>(new CompletableFuture<>(),
                ()->"CompletableFuture that will wait for the netty future to fill in the completion value");
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
                        channel.close();
                    }
                });
        return completableFuture;
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>
    finalizeRequest() {
        return activeChannelFuture.getDeferredFutureThroughHandle((v,t)-> {
                    var future = new CompletableFuture();
                    var rval = new DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>(future,
                            ()->"NettyPacketToHttpConsumer.finalizeRequest()");
                    if (t == null) {
                        var responseWatchHandler =
                                (BacksideHttpWatcherHandler) channel.pipeline().get(BACKSIDE_HTTP_WATCHER_HANDLER_NAME);
                        responseWatchHandler.addCallback(future::complete);
                    } else {
                        future.complete(responseBuilder.addErrorCause(t).build());
                    }
                    return rval;
                }, ()->"Waiting for previous consumes to set the callback")
                .map(f->f.whenComplete((v,t)->resetPipeline()), ()->"clearing pipeline");
    }
}
