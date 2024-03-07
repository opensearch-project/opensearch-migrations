package org.opensearch.migrations.replay.datahandlers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.http.helpers.ReadMeteringHandler;
import org.opensearch.migrations.replay.datahandlers.http.helpers.WriteMeteringHandler;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

import java.net.URI;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {

    /**
     * Set this to of(LogLevel.ERROR) or whatever level you'd like to get logging between each handler.
     * Set this to Optional.empty() to disable intra-handler logging.
     */
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();

    public static final String BACKSIDE_HTTP_WATCHER_HANDLER_NAME = "BACKSIDE_HTTP_WATCHER_HANDLER";
    public static final String SSL_HANDLER_NAME = "ssl";
    public static final String WRITE_COUNT_WATCHER_HANDLER_NAME = "writeCountWatcher";
    public static final String READ_COUNT_WATCHER_HANDLER_NAME = "readCountWatcher";
    /**
     * This is a future that chains work onto the channel.  If the value is ready, the future isn't waiting
     * on anything to happen for the channel.  If the future isn't done, something in the chain is still
     * pending.
     */
    DiagnosticTrackableCompletableFuture<String,Void> activeChannelFuture;
    private final Channel channel;
    AggregatedRawResponse.Builder responseBuilder;
    IWithTypedEnclosingScope<IReplayContexts.ITargetRequestContext> currentRequestContextUnion;

    private static class ConnectionClosedListenerHandler extends ChannelDuplexHandler {
        private final IReplayContexts.ISocketContext socketContext;
        ConnectionClosedListenerHandler(IReplayContexts.IChannelKeyContext channelKeyContext) {
            socketContext = channelKeyContext.createSocketContext();
        }
        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            socketContext.close();
            super.channelUnregistered(ctx);
        }
    }

    public NettyPacketToHttpConsumer(NioEventLoopGroup eventLoopGroup, URI serverUri, SslContext sslContext,
                                     IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext) {
        this(createClientConnection(eventLoopGroup, sslContext, serverUri,
                        httpTransactionContext.getLogicalEnclosingScope()), httpTransactionContext);
    }

    public NettyPacketToHttpConsumer(ChannelFuture clientConnection,
                                     IReplayContexts.IReplayerHttpTransactionContext ctx) {
        var parentContext = ctx.createTargetRequestContext();
        this.setCurrentMessageContext(parentContext.createHttpSendingContext());
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        DiagnosticTrackableCompletableFuture<String,Void>  initialFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<>(),
                        () -> "incoming connection is ready for " + clientConnection);
        this.activeChannelFuture = initialFuture;
        this.channel = clientConnection.channel();

        log.atDebug().setMessage(() ->
                "C'tor: incoming clientConnection pipeline=" + clientConnection.channel().pipeline()).log();
        clientConnection.addListener(connectFuture -> {
            if (connectFuture.isSuccess()) {
                activateChannelForThisConsumer();
            }
        }).addListener(completelySetupFuture -> {
            if (completelySetupFuture.isSuccess()) {
                initialFuture.future.complete(null);
            } else {
                initialFuture.future.completeExceptionally(completelySetupFuture.cause());
            }
        });
    }

    private <T extends IWithTypedEnclosingScope<IReplayContexts.ITargetRequestContext> &
            IScopedInstrumentationAttributes>
    void setCurrentMessageContext(T requestSendingContext) {
        currentRequestContextUnion = requestSendingContext;
    }

    private IScopedInstrumentationAttributes getCurrentRequestSpan() {
        return (IScopedInstrumentationAttributes) currentRequestContextUnion;
    }

    public IReplayContexts.ITargetRequestContext getParentContext() {
        return currentRequestContextUnion.getLogicalEnclosingScope();
    }
    
    public static ChannelFuture createClientConnection(EventLoopGroup eventLoopGroup,
                                                       SslContext sslContext,
                                                       URI serverUri,
                                                       IReplayContexts.IChannelKeyContext channelKeyContext) {
        String host = serverUri.getHost();
        int port = serverUri.getPort();
        log.atTrace().setMessage(()->"Active - setting up backend connection to " + host + ":" + port).log();

        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ConnectionClosedListenerHandler(channelKeyContext))
                .option(ChannelOption.AUTO_READ, false);

        var outboundChannelFuture = b.connect(host, port);

        var rval = new DefaultChannelPromise(outboundChannelFuture.channel());
        outboundChannelFuture.addListener((ChannelFutureListener) connectFuture -> {
            if (connectFuture.isSuccess()) {
                var pipeline = connectFuture.channel().pipeline();
                log.atTrace().setMessage(()-> channelKeyContext.getChannelKey() +
                        " Done setting up client channel & it was successful").log();
                if (sslContext != null) {
                    var sslEngine = sslContext.newEngine(connectFuture.channel().alloc());
                    sslEngine.setUseClientMode(true);
                    var sslHandler = new SslHandler(sslEngine);
                    addLoggingHandler(pipeline, "A");
                    pipeline.addLast(SSL_HANDLER_NAME, sslHandler);
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
                log.atWarn().setCause(connectFuture.cause())
                        .setMessage(() -> channelKeyContext.getChannelKey() + " CONNECT future was not successful, " +
                        "so setting the channel future's result to an exception").log();
                rval.setFailure(connectFuture.cause());
            }
        });
        return rval;
    }

    private static boolean channelIsInUse(Channel c) {
        var pipeline = c.pipeline();
        var lastHandler = pipeline.last();
        if (lastHandler instanceof ConnectionClosedListenerHandler || lastHandler instanceof SslHandler) {
            assert !c.config().isAutoRead();
            return false;
        } else {
            assert c.config().isAutoRead();
            return true;
        }
    }

    private void activateChannelForThisConsumer() {
        if (channelIsInUse(channel)) {
            throw new IllegalStateException("Channel " + channel + "is being used elsewhere already!");
        }
        var pipeline = channel.pipeline();
        // add these size counters BEFORE TLS?  Notice that when removing from the pipeline, we need to be more careful
        pipeline.addFirst(WRITE_COUNT_WATCHER_HANDLER_NAME, new WriteMeteringHandler(size->{
            // client side, so this is the request
            if (size == 0) { return; }
            if (!(this.currentRequestContextUnion instanceof IReplayContexts.IRequestSendingContext)) {
                this.getCurrentRequestSpan().close();
                this.setCurrentMessageContext(getParentContext().createHttpSendingContext());
            }
            getParentContext().onBytesSent(size);
        }));
        pipeline.addFirst(READ_COUNT_WATCHER_HANDLER_NAME, new ReadMeteringHandler(size->{
            // client side, so this is the response
            if (size == 0) { return; }
            if (!(this.currentRequestContextUnion instanceof IReplayContexts.IReceivingHttpResponseContext)) {
                this.getCurrentRequestSpan().close();
                this.setCurrentMessageContext(getParentContext().createHttpReceivingContext());
            }
            getParentContext().onBytesReceived(size);
        }));
        addLoggingHandler(pipeline, "B");
        pipeline.addLast(new BacksideSnifferHandler(responseBuilder));
        addLoggingHandler(pipeline, "C");
        pipeline.addLast(new HttpResponseDecoder());
        addLoggingHandler(pipeline, "D");
        // TODO - switch this out to use less memory.
        // We only need to know when the response has been fully received, not the contents
        // since we're already logging those in the sniffer earlier in the pipeline.
        pipeline.addLast(new HttpObjectAggregator(1024 * 1024));
        addLoggingHandler(pipeline, "D");
        pipeline.addLast(BACKSIDE_HTTP_WATCHER_HANDLER_NAME, new BacksideHttpWatcherHandler(responseBuilder));
        addLoggingHandler(pipeline, "E");
        log.atTrace().setMessage(() -> "Added handlers to the pipeline: " + pipeline).log();

        channel.config().setAutoRead(true);
    }

    private static void addLoggingHandler(ChannelPipeline pipeline, String name) {
        PIPELINE_LOGGING_OPTIONAL.ifPresent(logLevel->pipeline.addLast(new LoggingHandler("n"+name, logLevel)));
    }

    private void deactivateChannel() {
        try {
            var pipeline = channel.pipeline();
            log.atDebug().setMessage(() -> "Resetting the pipeline currently at: " + pipeline).log();
            for (var handlerName : new String[]{WRITE_COUNT_WATCHER_HANDLER_NAME, READ_COUNT_WATCHER_HANDLER_NAME}) {
                try {
                    pipeline.remove(handlerName);
                } catch (NoSuchElementException e) {
                    log.atDebug().setMessage(()->"Ignoring an exception that the "+handlerName+" wasn't present").log();
                }
            }
            while (true) {
                var lastHandler = pipeline.last();
                if (lastHandler instanceof SslHandler || lastHandler instanceof ConnectionClosedListenerHandler) {
                    break;
                }
                pipeline.removeLast();
            }
            channel.config().setAutoRead(false);
            log.atDebug().setMessage(() -> "Reset the pipeline back to: " + pipeline).log();
        } finally {
            getCurrentRequestSpan().close();
            getParentContext().close();
        }
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String,Void> consumeBytes(ByteBuf packetData) {
        activeChannelFuture = activeChannelFuture.getDeferredFutureThroughHandle((v, channelInitException) -> {
            if (channelInitException == null) {
                log.atTrace().setMessage(()->"outboundChannelFuture is ready writing packets (hash=" +
                        System.identityHashCode(packetData) + ")").log();
                return writePacketAndUpdateFuture(packetData);
            } else {
                log.atWarn().setMessage(()-> httpContext().getReplayerRequestKey() +
                        "outbound channel was not set up successfully, NOT writing bytes hash=" +
                        System.identityHashCode(packetData)).log();
                channel.close();
                return DiagnosticTrackableCompletableFuture.Factory.failedFuture(channelInitException, ()->"");
            }
        }, ()->"consumeBytes - after channel is fully initialized (potentially waiting on TLS handshake)");
        log.atTrace().setMessage(()->"Setting up write of packetData["+packetData+"] hash=" +
                System.identityHashCode(packetData) + ".  Created future consumeBytes="+activeChannelFuture).log();
        return activeChannelFuture;
    }

    private IReplayContexts.IReplayerHttpTransactionContext httpContext() {
        return getParentContext().getLogicalEnclosingScope();
    }

    private DiagnosticTrackableCompletableFuture<String, Void>
    writePacketAndUpdateFuture(ByteBuf packetData) {
        final var completableFuture = new DiagnosticTrackableCompletableFuture<String, Void>(new CompletableFuture<>(),
                ()->"CompletableFuture that will wait for the netty future to fill in the completion value");
        final int readableBytes = packetData.readableBytes();
        channel.writeAndFlush(packetData)
                .addListener((ChannelFutureListener) future -> {
                    Throwable cause = null;
                    try {
                        if (!future.isSuccess()) {
                            log.atWarn().setMessage(()-> httpContext().getReplayerRequestKey() + "closing outbound channel " +
                                    "because WRITE future was not successful " + future.cause() + " hash=" +
                                    System.identityHashCode(packetData) + " will be sending the exception to " +
                                    completableFuture).log();
                            future.channel().close(); // close the backside
                            cause = future.cause();
                        }
                    } catch (Exception e) {
                        cause = e;
                    }
                    if (cause == null) {
                        log.atTrace().setMessage(()->"Previously returned CompletableFuture packet write was " +
                                "successful: " + packetData + " hash=" + System.identityHashCode(packetData)).log();
                        completableFuture.future.complete(null);
                    } else {
                        log.atInfo().setMessage(()->"Previously returned CompletableFuture packet write had " +
                                " an exception :" + packetData + " hash=" + System.identityHashCode(packetData)).log();
                        completableFuture.future.completeExceptionally(cause);
                        channel.close();
                    }
                });
        log.atTrace().setMessage(()->"Writing packet data=" + packetData +
                ".  Created future for writing data="+completableFuture).log();
        return completableFuture;
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>
    finalizeRequest() {
        var ff = activeChannelFuture.getDeferredFutureThroughHandle((v,t)-> {
                    if (!(this.currentRequestContextUnion instanceof IReplayContexts.IReceivingHttpResponseContext)) {
                        this.getCurrentRequestSpan().close();
                        this.setCurrentMessageContext(getParentContext().createWaitingForResponseContext());
                    }

                    var future = new CompletableFuture<AggregatedRawResponse>();
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
                }, ()->"Waiting for previous consumes to set the future")
                .map(f->f.whenComplete((v,t)-> deactivateChannel()), ()->"clearing pipeline");
        log.atTrace().setMessage(()->"Chaining finalization work off of " + activeChannelFuture +
                ".  Returning finalization future="+ff).log();
        return ff;
    }
}
