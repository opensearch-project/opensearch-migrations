package org.opensearch.migrations.replay;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientConnectionPool {

    private final URI serverUri;
    private final SslContext sslContext;
    public final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<Key, ConnectionReplaySession> connectionId2ChannelCache;

    @EqualsAndHashCode
    @AllArgsConstructor
    private static class Key {
        private final String connectionId;
        private final int sessionNumber;
    }

    private Key getKey(String connectionId, int sessionNumber) {
        return new Key(connectionId, sessionNumber);
    }

    public ClientConnectionPool(
        @NonNull URI serverUri,
        SslContext sslContext,
        @NonNull String targetConnectionPoolName,
        int numThreads
    ) {
        this.serverUri = serverUri;
        this.sslContext = sslContext;
        this.eventLoopGroup = new NioEventLoopGroup(numThreads, new DefaultThreadFactory(targetConnectionPoolName));

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(CacheLoader.from(key -> {
            throw new UnsupportedOperationException("Use Cache.get(key, callable) instead");
        }));
    }

    public ConnectionReplaySession buildConnectionReplaySession(IReplayContexts.IChannelKeyContext channelKeyCtx) {
        if (eventLoopGroup.isShuttingDown()) {
            throw new IllegalStateException("Event loop group is shutting down.  Not creating a new session.");
        }
        // arguably the most only thing that matters here is associating this item with an
        // EventLoop (thread). As the channel needs to be recycled, we'll come back to the
        // event loop that was tied to the original channel to bind all future channels to
        // the same event loop. That means that we don't have to worry about concurrent
        // accesses/changes to the OTHER value that we're storing within the cache.
        var eventLoop = eventLoopGroup.next();
        return new ConnectionReplaySession(
            eventLoop,
            channelKeyCtx,
            () -> getResilientClientChannelProducer(eventLoop, channelKeyCtx)
        );
    }

    private TrackedFuture<String, ChannelFuture> getResilientClientChannelProducer(
        EventLoop eventLoop,
        IReplayContexts.IChannelKeyContext connectionContext
    ) {
        return new AdaptiveRateLimiter<String, ChannelFuture>().get(
            () -> NettyPacketToHttpConsumer.createClientConnection(eventLoop, sslContext, serverUri, connectionContext)
                .whenComplete((v, t) -> {
                    if (t == null) {
                        log.atDebug()
                            .setMessage(() -> "New network connection result for " + connectionContext + " =" + v)
                            .log();
                    } else {
                        log.atInfo().setMessage(() -> "got exception for " + connectionContext).setCause(t).log();
                    }
                }, () -> "waiting for createClientConnection to finish")
        );
    }

    public CompletableFuture<Void> shutdownNow() {
        CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();
        connectionId2ChannelCache.invalidateAll();
        return NettyFutureBinders.bindNettyFutureToCompletableFuture(eventLoopGroup.shutdownGracefully());
    }

    public void closeConnection(IReplayContexts.IChannelKeyContext ctx, int sessionNumber) {
        var connId = ctx.getConnectionId();
        log.atInfo().setMessage(() -> "closing connection for " + connId).log();
        var connectionReplaySession = connectionId2ChannelCache.getIfPresent(getKey(connId, sessionNumber));
        if (connectionReplaySession != null) {
            closeClientConnectionChannel(connectionReplaySession);
            connectionId2ChannelCache.invalidate(connId);
        } else {
            log.atInfo()
                .setMessage(
                    () -> "No ChannelFuture for "
                        + ctx
                        + " in closeConnection.  The connection may have already been closed"
                )
                .log();
        }
    }

    @SneakyThrows
    public @NonNull ConnectionReplaySession getCachedSession(
        IReplayContexts.IChannelKeyContext channelKeyCtx,
        int sessionNumber
    ) {
        var crs = connectionId2ChannelCache.get(
            getKey(channelKeyCtx.getConnectionId(), sessionNumber),
            () -> buildConnectionReplaySession(channelKeyCtx)
        );
        log.atTrace()
            .setMessage(
                () -> "returning ReplaySession="
                    + crs
                    + " for "
                    + channelKeyCtx.getConnectionId()
                    + " from "
                    + channelKeyCtx
            )
            .log();
        return crs;
    }

    private TrackedFuture<String, Channel> closeClientConnectionChannel(ConnectionReplaySession channelAndFutureWork) {
        return channelAndFutureWork.getFutureThatReturnsChannelFutureInAnyState(false).thenCompose(channelFuture -> {
            if (channelFuture == null) {
                log.atTrace()
                    .setMessage(
                        () -> "Asked to close channel for "
                            + channelAndFutureWork.getChannelKeyContext()
                            + " but the channel wasn't found.  "
                            + "It may have already been reset."
                    )
                    .log();
                return TextTrackedFuture.completedFuture(null, () -> "");
            }
            log.atTrace()
                .setMessage(
                    () -> "closing channel "
                        + channelFuture.channel()
                        + "("
                        + channelAndFutureWork.getChannelKeyContext()
                        + ")..."
                )
                .log();
            return NettyFutureBinders.bindNettyFutureToTrackableFuture(
                channelFuture.channel().close(),
                "calling channel.close()"
            ).thenApply(v -> {
                log.atTrace()
                    .setMessage(
                        () -> "channel.close() has finished for "
                            + channelAndFutureWork.getChannelKeyContext()
                            + " with value="
                            + v
                    )
                    .log();
                if (channelAndFutureWork.hasWorkRemaining()) {
                    log.atWarn()
                        .setMessage(
                            () -> "Work items are still remaining for this connection session"
                                + "(last associated with connection="
                                + channelAndFutureWork.getChannelKeyContext()
                                + ").  "
                                + channelAndFutureWork.calculateSizeSlowly()
                                + " requests that were enqueued won't be run"
                        )
                        .log();
                }
                channelAndFutureWork.schedule.clear();
                return channelFuture.channel();
            }, () -> "clearing work");
        }, () -> "");
    }
}
