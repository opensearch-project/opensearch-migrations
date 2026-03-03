package org.opensearch.migrations.replay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientConnectionPool {

    private final BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>>
        channelCreator;
    private final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<Key, ConnectionReplaySession> connectionId2ChannelCache;
    /** Called when any session's channel is closed. Default no-op; set by coordinator. */
    @Setter
    private Consumer<ConnectionReplaySession> globalOnSessionClose = session -> {};

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
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelCreator,
        @NonNull String targetConnectionPoolName,
        int numThreads
    ) {
        this.channelCreator = channelCreator;
        this.eventLoopGroup = new NioEventLoopGroup(numThreads, new DefaultThreadFactory(targetConnectionPoolName));

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(CacheLoader.from(key -> {
            throw new UnsupportedOperationException("Use Cache.get(key, callable) instead");
        }));
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable,
                                                  long initialDelay,
                                                  long delay,
                                                  TimeUnit timeUnit) {
        return eventLoopGroup.next().scheduleAtFixedRate(runnable, initialDelay, delay, timeUnit);
    }

    public ConnectionReplaySession buildConnectionReplaySession(IReplayContexts.IChannelKeyContext channelKeyCtx) {
        return buildConnectionReplaySession(channelKeyCtx, 0);
    }

    public ConnectionReplaySession buildConnectionReplaySession(IReplayContexts.IChannelKeyContext channelKeyCtx, int generation) {
        if (eventLoopGroup.isShuttingDown()) {
            throw new IllegalStateException("Event loop group is shutting down.  Not creating a new session.");
        }
        // arguably the most only thing that matters here is associating this item with an
        // EventLoop (thread). As the channel needs to be recycled, we'll come back to the
        // event loop that was tied to the original channel to bind all future channels to
        // the same event loop. That means that we don't have to worry about concurrent
        // accesses/changes to the OTHER value that we're storing within the cache.
        return new ConnectionReplaySession(eventLoopGroup.next(), channelKeyCtx, channelCreator, generation,
            globalOnSessionClose);
    }

    @SneakyThrows
    public @NonNull ConnectionReplaySession getCachedSession(
        IReplayContexts.IChannelKeyContext channelKeyCtx,
        int sessionNumber
    ) {
        return getCachedSession(channelKeyCtx, sessionNumber, 0);
    }

    @SneakyThrows
    public @NonNull ConnectionReplaySession getCachedSession(
        IReplayContexts.IChannelKeyContext channelKeyCtx,
        int sessionNumber,
        int generation
    ) {
        var key = getKey(channelKeyCtx.getConnectionId(), sessionNumber);
        var crs = connectionId2ChannelCache.get(
            key,
            () -> buildConnectionReplaySession(channelKeyCtx, generation)
        );
        log.atTrace()
            .setMessage("returning ReplaySession={} (gen={}) for {} from {}")
            .addArgument(crs)
            .addArgument(crs.generation)
            .addArgument(channelKeyCtx::getConnectionId)
            .addArgument(channelKeyCtx)
            .log();
        return crs;
    }

    public void closeConnection(IReplayContexts.IChannelKeyContext ctx, int sessionNumber) {
        var connId = ctx.getConnectionId();
        log.atTrace().setMessage("closing connection for {}").addArgument(connId).log();
        var connectionReplaySession = connectionId2ChannelCache.getIfPresent(getKey(connId, sessionNumber));
        if (connectionReplaySession != null) {
            closeClientConnectionChannel(connectionReplaySession);
            connectionId2ChannelCache.invalidate(getKey(connId, sessionNumber));
        } else {
            log.atTrace()
                .setMessage("No ChannelFuture for {} in closeConnection.  " +
                        "The connection may have already been closed")
                .addArgument(ctx)
                .log();
        }
    }

    /** Closes the Netty channel for a session without touching the cache. */
    public TrackedFuture<String, Channel> closeChannelForSession(ConnectionReplaySession session) {
        return closeClientConnectionChannel(session);
    }

    /**
     * Immediately cancels a connection: marks the session cancelled (prevents reconnection),
     * completes all pending scheduleFuture entries exceptionally so the OnlineRadixSorter
     * drains fast (releasing requestWorkTracker entries and TrafficStreamLimiter slots),
     * then closes the channel and invalidates the cache.
     */
    public TrackedFuture<String, Void> cancelConnection(IReplayContexts.IChannelKeyContext ctx, int sessionNumber) {
        var connId = ctx.getConnectionId();
        var session = connectionId2ChannelCache.getIfPresent(getKey(connId, sessionNumber));
        if (session != null) {
            session.setCancelled(true);
            // Cancel all pending sorter slots on the event loop thread so they drain immediately.
            // This prevents orphaned scheduleFuture entries from leaving requestWorkTracker
            // entries and TrafficStreamLimiter slots unreleased.
            session.eventLoop.submit(() -> session.scheduleSequencer.cancelAllWork());
            closeConnection(ctx, sessionNumber);
        }
        return TextTrackedFuture.completedFuture(null, () -> "cancelled");
    }

    public void invalidateSession(String connectionId, int sessionNumber) {
        connectionId2ChannelCache.invalidate(getKey(connectionId, sessionNumber));
    }

    public CompletableFuture<Void> shutdownNow() {
        log.atInfo().setMessage("Shutting down ClientConnectionPool").log();
        var rval = NettyFutureBinders.bindNettyFutureToCompletableFuture(eventLoopGroup.shutdownGracefully());
        connectionId2ChannelCache.invalidateAll();
        return rval;
    }

    private TrackedFuture<String, Channel> closeClientConnectionChannel(ConnectionReplaySession session) {
        return session
            .getChannelFutureInAnyState() // this could throw, especially if the even loop has begun to shut down
            .thenCompose(channelFuture -> {
                if (channelFuture == null) {
                    log.atTrace().setMessage("Couldn't find the channel for {} to close it.  " +
                            "It may have already been reset.")
                        .addArgument(session::getChannelKeyContext)
                        .log();
                    session.onClose.accept(session);
                    return TextTrackedFuture.completedFuture(null, () -> "");
                }
                log.atTrace().setMessage("closing channel {} ({})...")
                    .addArgument(channelFuture::channel)
                    .addArgument(session::getChannelKeyContext)
                    .log();

                return NettyFutureBinders.bindNettyFutureToTrackableFuture(
                        channelFuture.channel().close(), "calling channel.close()")
                    .thenApply(v -> {
                        log.atTrace().setMessage("channel.close() has finished for {} with value={}")
                            .addArgument(session::getChannelKeyContext)
                            .addArgument(v)
                            .log();
                        if (session.hasWorkRemaining()) {
                            log.atWarn().setMessage("Work items are still remaining for this connection session " +
                                    "(last associated with connection={}). {} requests that were enqueued won't be run")
                                .addArgument(session::getChannelKeyContext)
                                .addArgument(session::calculateSizeSlowly)
                                .log();
                        }
                        session.schedule.clear();
                        session.onClose.accept(session);
                        return channelFuture.channel();
                    }, () -> "clearing work");
            }, () -> "composing close through retrieved channel from the session");
    }
}
