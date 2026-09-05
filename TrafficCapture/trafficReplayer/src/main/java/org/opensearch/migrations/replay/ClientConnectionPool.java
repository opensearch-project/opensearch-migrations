package org.opensearch.migrations.replay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
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
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientConnectionPool {

    private final BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>>
        channelCreator;
    private final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<Key, ConnectionReplaySession> connectionId2ChannelCache;
    private final TargetExchangeState.Metrics metrics;

    @EqualsAndHashCode
    @AllArgsConstructor
    private static class Key {
        private final String connectionId;
        private final int sessionNumber;
        private final int sourceGeneration;
    }

    private Key getKey(String connectionId, int sessionNumber) {
        return getKey(connectionId, sessionNumber, 0);
    }

    private Key getKey(String connectionId, int sessionNumber, int sourceGeneration) {
        return new Key(connectionId, sessionNumber, sourceGeneration);
    }

    public ClientConnectionPool(
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelCreator,
        @NonNull String targetConnectionPoolName,
        int numThreads
    ) {
        this(channelCreator, targetConnectionPoolName, numThreads, TargetExchangeState.Metrics.NOOP);
    }

    public ClientConnectionPool(
        BiFunction<EventLoop, IReplayContexts.ITargetRequestContext, TrackedFuture<String, ChannelFuture>> channelCreator,
        @NonNull String targetConnectionPoolName,
        int numThreads,
        @NonNull TargetExchangeState.Metrics metrics
    ) {
        this.channelCreator = channelCreator;
        this.metrics = metrics;
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
        return new ConnectionReplaySession(eventLoopGroup.next(), channelKeyCtx, channelCreator, generation, metrics);
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
        var key = getKey(channelKeyCtx.getConnectionId(), sessionNumber, generation);
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

    /** Closes the Netty channel for a session without touching the cache. */
    public TrackedFuture<String, Channel> closeChannelForSession(ConnectionReplaySession session) {
        return closeClientConnectionChannel(session);
    }

    public void invalidateSession(String connectionId, int sessionNumber) {
        connectionId2ChannelCache.asMap().keySet().stream()
            .filter(key -> key.connectionId.equals(connectionId) && key.sessionNumber == sessionNumber)
            .toList()
            .forEach(this::retireAndInvalidate);
    }

    public void invalidateSession(String connectionId, int sessionNumber, int sourceGeneration) {
        retireAndInvalidate(getKey(connectionId, sessionNumber, sourceGeneration));
    }

    public CompletableFuture<Void> shutdownNow() {
        log.atInfo().setMessage("Shutting down ClientConnectionPool").log();
        connectionId2ChannelCache.asMap().values().forEach(ConnectionReplaySession::retireMetrics);
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
                    return TextTrackedFuture.completedFuture(null, () -> "");
                }
                log.atTrace().setMessage("closing channel {} ({})...")
                    .addArgument(channelFuture::channel)
                    .addArgument(session::getChannelKeyContext)
                    .log();
                session.markChannelClosing(channelFuture);

                return NettyFutureBinders.bindNettyFutureToTrackableFuture(
                        channelFuture.channel().close(), "calling channel.close()")
                    .thenApply(v -> {
                        log.atTrace().setMessage("channel.close() has finished for {} with value={}")
                            .addArgument(session::getChannelKeyContext)
                            .addArgument(v)
                            .log();
                        return channelFuture.channel();
                    }, () -> "clearing work");
            }, () -> "composing close through retrieved channel from the session");
    }

    private void retireAndInvalidate(Key key) {
        var session = connectionId2ChannelCache.getIfPresent(key);
        if (session != null) {
            session.retireMetrics();
        }
        connectionId2ChannelCache.invalidate(key);
    }
}
