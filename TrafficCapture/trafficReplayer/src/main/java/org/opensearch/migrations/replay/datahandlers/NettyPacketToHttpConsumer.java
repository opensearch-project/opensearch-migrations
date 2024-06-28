package org.opensearch.migrations.replay.datahandlers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.http.helpers.ReadMeteringHandler;
import org.opensearch.migrations.replay.datahandlers.http.helpers.WriteMeteringHandler;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {

    /**
     * Set this to of(LogLevel.ERROR) or whatever level you'd like to get logging between each handler.
     * Set this to Optional.empty() to disable intra-handler logging.
     */
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();

    public static final String BACKSIDE_HTTP_WATCHER_HANDLER_NAME = "BACKSIDE_HTTP_WATCHER_HANDLER";
    public static final String CONNECTION_CLOSE_HANDLER_NAME = "CONNECTION_CLOSE_HANDLER";
    public static final String SSL_HANDLER_NAME = "ssl";
    public static final String READ_TIMEOUT_HANDLER_NAME = "readTimeoutHandler";
    public static final String WRITE_COUNT_WATCHER_HANDLER_NAME = "writeCountWatcher";
    public static final String READ_COUNT_WATCHER_HANDLER_NAME = "readCountWatcher";

    /**
     * This is a future that chains work onto the channel.  If the value is ready, the future isn't waiting
     * on anything to happen for the channel.  If the future isn't done, something in the chain is still
     * pending.
     */
    TrackedFuture<String, Void> activeChannelFuture;
    ConnectionReplaySession replaySession;
    private Channel channel;
    AggregatedRawResponse.Builder responseBuilder;
    IWithTypedEnclosingScope<IReplayContexts.ITargetRequestContext> currentRequestContextUnion;
    Duration readTimeoutDuration;

    private static class ConnectionClosedListenerHandler extends ChannelInboundHandlerAdapter {
        private final IReplayContexts.ISocketContext socketContext;

        ConnectionClosedListenerHandler(IReplayContexts.IChannelKeyContext channelKeyContext) {
            socketContext = channelKeyContext.createSocketContext();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            socketContext.close();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            socketContext.addTraceException(cause, true);
            log.atDebug()
                .setMessage("Exception caught in ConnectionClosedListenerHandler." + "Closing channel due to exception")
                .setCause(cause)
                .log();
            ctx.close();
            super.exceptionCaught(ctx, cause);
        }
    }

    public NettyPacketToHttpConsumer(
        ConnectionReplaySession replaySession,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Duration readTimeoutDuration
    ) {
        this.replaySession = replaySession;
        var parentContext = ctx.createTargetRequestContext();
        this.setCurrentMessageContext(parentContext.createHttpSendingContext());
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        log.atDebug().setMessage(() -> "C'tor: incoming session=" + replaySession).log();
        this.activeChannelFuture = activateLiveChannel();
        this.readTimeoutDuration = readTimeoutDuration;
    }

    private TrackedFuture<String, Void> activateLiveChannel() {
        final var ctx = replaySession.getChannelKeyContext();
        return replaySession.getFutureThatReturnsChannelFutureInAnyState(true)
            .thenCompose(
                channelFuture -> NettyFutureBinders.bindNettyFutureToTrackableFuture(
                    channelFuture,
                    "waiting for newly acquired channel to be ready"
                ).getDeferredFutureThroughHandle((connectFuture, t) -> {
                    if (t != null) {
                        ctx.addFailedChannelCreation();
                        ctx.addTraceException(channelFuture.cause(), true);
                        log.atWarn().setMessage(() -> "error creating channel, not retrying").setCause(t).log();
                        throw Lombok.sneakyThrow(t);
                    }

                    final var c = channelFuture.channel();
                    if (c.isActive()) {
                        this.channel = c;
                        initializeChannelPipeline();
                        log.atDebug().setMessage(() -> "Channel initialized for " + ctx + " signaling future").log();
                        return TextTrackedFuture.completedFuture(null, () -> "Done");
                    } else {
                        // this may recurse forever - until the event loop is shutdown
                        // (see the ClientConnectionPool::shutdownNow())
                        ctx.addFailedChannelCreation();
                        log.atWarn().setMessage(() -> "Channel wasn't active, trying to create another for this request").log();
                        return activateLiveChannel();
                    }
                }, () -> "acting on ready channelFuture to retry if inactive or to return"),
                () -> "taking newly acquired channel and making it active"
            );
    }

    private <
        T extends IWithTypedEnclosingScope<IReplayContexts.ITargetRequestContext> & IScopedInstrumentationAttributes>
        void
        setCurrentMessageContext(T requestSendingContext) {
        currentRequestContextUnion = requestSendingContext;
    }

    private IScopedInstrumentationAttributes getCurrentRequestSpan() {
        return (IScopedInstrumentationAttributes) currentRequestContextUnion;
    }

    public IReplayContexts.ITargetRequestContext getParentContext() {
        return currentRequestContextUnion.getLogicalEnclosingScope();
    }

    public static TrackedFuture<String, ChannelFuture> createClientConnection(
        EventLoopGroup eventLoopGroup,
        SslContext sslContext,
        URI serverUri,
        IReplayContexts.IChannelKeyContext channelKeyContext
    ) {
        String host = serverUri.getHost();
        int port = serverUri.getPort();
        log.atTrace().setMessage(() -> "Active - setting up backend connection to " + host + ":" + port).log();

        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup).handler(new ChannelInitializer<>() {
            @Override
            protected void initChannel(@NonNull Channel ch) throws Exception {
                ch.pipeline().addFirst(CONNECTION_CLOSE_HANDLER_NAME, new ConnectionClosedListenerHandler(channelKeyContext));
            }
        }).channel(NioSocketChannel.class).option(ChannelOption.AUTO_READ, false);

        var outboundChannelFuture = b.connect(host, port);

        return NettyFutureBinders.bindNettyFutureToTrackableFuture(outboundChannelFuture, "").thenCompose(voidVal -> {
            if (outboundChannelFuture.isSuccess()) {
                final var channel = outboundChannelFuture.channel();
                log.atTrace()
                    .setMessage(
                        () -> channelKeyContext.getChannelKey() + " Done setting up client channel & it was successful for " + channel
                    )
                    .log();
                var pipeline = channel.pipeline();
                if (sslContext != null) {
                    var sslEngine = sslContext.newEngine(channel.alloc());
                    sslEngine.setUseClientMode(true);
                    var sslHandler = new SslHandler(sslEngine);
                    addLoggingHandlerLast(pipeline, "A");
                    pipeline.addLast(SSL_HANDLER_NAME, sslHandler);
                    return NettyFutureBinders.bindNettyFutureToTrackableFuture(sslHandler.handshakeFuture(), () -> "")
                        .thenApply(voidVal2 -> outboundChannelFuture, () -> "");
                } else {
                    return TextTrackedFuture.completedFuture(outboundChannelFuture, () -> "");
                }
            } else {
                return TextTrackedFuture.failedFuture(outboundChannelFuture.cause(), () -> "");
            }
        }, () -> "");
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

    private void initializeChannelPipeline() {
        assert channel.isActive();
        if (channelIsInUse(channel)) {
            throw new IllegalStateException("Channel " + channel + "is being used elsewhere already!");
        }
        var pipeline = channel.pipeline();
        // add these size counters BEFORE TLS? Notice that when removing from the pipeline, we need to be more careful
        pipeline.addAfter(CONNECTION_CLOSE_HANDLER_NAME, WRITE_COUNT_WATCHER_HANDLER_NAME, new WriteMeteringHandler(size -> {
            // client side, so this is the request
            if (size == 0) {
                return;
            }
            if (!(this.currentRequestContextUnion instanceof IReplayContexts.IRequestSendingContext)) {
                this.getCurrentRequestSpan().close();
                this.setCurrentMessageContext(getParentContext().createHttpSendingContext());
            }
            getParentContext().onBytesSent(size);
        }));
        pipeline.addAfter(CONNECTION_CLOSE_HANDLER_NAME, READ_COUNT_WATCHER_HANDLER_NAME, new ReadMeteringHandler(size -> {
            // client side, so this is the response
            if (size == 0) {
                return;
            }
            if (!(this.currentRequestContextUnion instanceof IReplayContexts.IReceivingHttpResponseContext)) {
                this.getCurrentRequestSpan().close();
                this.setCurrentMessageContext(getParentContext().createHttpReceivingContext());
            }
            getParentContext().onBytesReceived(size);
        }));
        pipeline.addLast(READ_TIMEOUT_HANDLER_NAME, new ReadTimeoutHandler(this.readTimeoutDuration.toMillis(), TimeUnit.MILLISECONDS));
        addLoggingHandlerLast(pipeline, "B");
        pipeline.addLast(new BacksideSnifferHandler(responseBuilder));
        addLoggingHandlerLast(pipeline, "C");
        pipeline.addLast(new HttpResponseDecoder());
        addLoggingHandlerLast(pipeline, "D");
        pipeline.addLast(BACKSIDE_HTTP_WATCHER_HANDLER_NAME, new BacksideHttpWatcherHandler(responseBuilder));
        addLoggingHandlerLast(pipeline, "E");
        log.atTrace().setMessage(() -> "Added handlers to the pipeline: " + pipeline).log();

        channel.config().setAutoRead(true);
    }

    private static void addLoggingHandlerLast(ChannelPipeline pipeline, String name) {
        PIPELINE_LOGGING_OPTIONAL.ifPresent(logLevel -> pipeline.addLast(new LoggingHandler("n" + name, logLevel)));
    }

    private void deactivateChannel() {
        try {
            var pipeline = channel.pipeline();
            log.atDebug().setMessage(() -> "Resetting the pipeline for channel " + channel + "currently at: " + pipeline).log();
            for (var handlerName : new String[] { WRITE_COUNT_WATCHER_HANDLER_NAME, READ_COUNT_WATCHER_HANDLER_NAME }) {
                try {
                    pipeline.remove(handlerName);
                } catch (NoSuchElementException e) {
                    log.atWarn().setMessage(() -> "Ignoring an exception that the " + handlerName + " wasn't present").log();
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
            log.atDebug().setMessage(() -> "Reset the pipeline for channel " + channel + " back to: " + pipeline).log();
        } finally {
            getCurrentRequestSpan().close();
            getParentContext().close();
        }
    }

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf packetData) {
        activeChannelFuture = activeChannelFuture.getDeferredFutureThroughHandle((v, channelException) -> {
            if (channelException == null) {
                log.atTrace()
                    .setMessage("{}")
                    .addArgument(
                        () -> "outboundChannelFuture is ready. Writing packets (hash="
                            + System.identityHashCode(packetData)
                            + "): "
                            + httpContext()
                            + ": "
                            + packetData.toString(StandardCharsets.UTF_8)
                    )
                    .log();
                return writePacketAndUpdateFuture(packetData).whenComplete((v2, t2) -> {
                    log.atTrace().setMessage(() -> "finished writing " + httpContext() + " t=" + t2).log();
                }, () -> "");
            } else {
                log.atWarn()
                    .setMessage(
                        () -> httpContext().getReplayerRequestKey()
                            + "outbound channel was not set up successfully, NOT writing bytes hash="
                            + System.identityHashCode(packetData)
                    )
                    .log();
                channel.close();
                return TrackedFuture.Factory.failedFuture(channelException, () -> "exception");
            }
        }, () -> "consumeBytes - after channel is fully initialized (potentially waiting on TLS handshake)");
        log.atTrace()
            .setMessage(
                () -> "Setting up write of packetData["
                    + packetData
                    + "] hash="
                    + System.identityHashCode(packetData)
                    + ".  Created future consumeBytes="
                    + activeChannelFuture
            )
            .log();
        return activeChannelFuture;
    }

    private IReplayContexts.IReplayerHttpTransactionContext httpContext() {
        return getParentContext().getLogicalEnclosingScope();
    }

    private TrackedFuture<String, Void> writePacketAndUpdateFuture(ByteBuf packetData) {
        return NettyFutureBinders.bindNettyFutureToTrackableFuture(
            channel.writeAndFlush(packetData),
            "CompletableFuture that will wait for the netty future to fill in the completion value"
        );
    }

    @Override
    public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
        var ff = activeChannelFuture.getDeferredFutureThroughHandle((v, t) -> {
            log.atDebug().setMessage(() -> "finalization running since all prior work has completed for " + httpContext()).log();
            if (!(this.currentRequestContextUnion instanceof IReplayContexts.IReceivingHttpResponseContext)) {
                this.getCurrentRequestSpan().close();
                this.setCurrentMessageContext(getParentContext().createWaitingForResponseContext());
            }

            var future = new CompletableFuture<AggregatedRawResponse>();
            var rval = new TrackedFuture<String, AggregatedRawResponse>(future, () -> "NettyPacketToHttpConsumer.finalizeRequest()");
            if (t == null) {
                var responseWatchHandler = (BacksideHttpWatcherHandler) channel.pipeline().get(BACKSIDE_HTTP_WATCHER_HANDLER_NAME);
                responseWatchHandler.addCallback(future::complete);
            } else {
                future.complete(responseBuilder.addErrorCause(t).build());
            }
            return rval;
        }, () -> "Waiting for previous consumes to set the future").map(f -> f.whenComplete((v, t) -> {
            if (channel == null) {
                log.atInfo().setMessage(() -> "").log();
            } else {
                deactivateChannel();
            }
        }), () -> "clearing pipeline");
        log.atDebug()
            .setMessage(
                () -> "Chaining finalization work off of "
                    + activeChannelFuture
                    + " for "
                    + httpContext()
                    + ".  Returning finalization future="
                    + ff
            )
            .log();
        return ff;
    }
}
