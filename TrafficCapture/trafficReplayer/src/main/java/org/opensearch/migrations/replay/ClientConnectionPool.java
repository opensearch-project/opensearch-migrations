package org.opensearch.migrations.replay;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.opentelemetry.context.ContextKey;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
public class ClientConnectionPool {
    public static final String TARGET_CONNECTION_POOL_NAME = "targetConnectionPool";
    private final URI serverUri;
    private final SslContext sslContext;
    public final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<String, ConnectionReplaySession> connectionId2ChannelCache;

    public ConnectionReplaySession buildConnectionReplaySession(final IReplayContexts.IChannelKeyContext channelKeyCtx) {
        if (eventLoopGroup.isShuttingDown()) {
            throw new IllegalStateException("Event loop group is shutting down.  Not creating a new session.");
        }
        log.warn("creating connection session");
        // arguably the most only thing that matters here is associating this item with an
        // EventLoop (thread).  As the channel needs to be recycled, we'll come back to the
        // event loop that was tied to the original channel to bind all future channels to
        // the same event loop.  That means that we don't have to worry about concurrent
        // accesses/changes to the OTHER value that we're storing within the cache.
        return new ConnectionReplaySession(eventLoopGroup.next(), channelKeyCtx);
    }

    public ClientConnectionPool(URI serverUri, SslContext sslContext, int numThreads) {
        this.serverUri = serverUri;
        this.sslContext = sslContext;
        this.eventLoopGroup =
                new NioEventLoopGroup(numThreads, new DefaultThreadFactory(TARGET_CONNECTION_POOL_NAME));

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(CacheLoader.from(key -> {
            throw new UnsupportedOperationException("Use Cache.get(key, callable) instead");
        }));
    }

    private DiagnosticTrackableCompletableFuture<String, ChannelFuture>
    getResilientClientChannelProducer(EventLoop eventLoop, IReplayContexts.IChannelKeyContext connectionContext) {
        return new AdaptiveRateLimiter<String, ChannelFuture>()
                .get(() -> {
                    var clientConnectionChannelCreatedFuture =
                            new StringTrackableCompletableFuture<ChannelFuture>(new CompletableFuture<>(),
                                    () -> "waiting for createClientConnection to finish");
                    var channelFuture = NettyPacketToHttpConsumer.createClientConnection(eventLoop,
                            sslContext, serverUri, connectionContext);
                    channelFuture.addListener(f -> {
                        log.atInfo().setMessage(()->
                                "New network connection result for " + connectionContext + "=" + f.isSuccess()).log();
                        if (f.isSuccess()) {
                            clientConnectionChannelCreatedFuture.future.complete(channelFuture);
                        } else {
                            clientConnectionChannelCreatedFuture.future.completeExceptionally(f.cause());
                        }
                    });
                    return clientConnectionChannelCreatedFuture;
                });
    }

    public Future shutdownNow() {
        connectionId2ChannelCache.invalidateAll();
        return eventLoopGroup.shutdownGracefully();
    }

    public DiagnosticTrackableCompletableFuture<String, Void> closeConnectionsAndShutdown() {
        StringTrackableCompletableFuture<Void> eventLoopFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<>(), () -> "all channels closed");
        this.eventLoopGroup.submit(() -> {
            try {
                var channelClosedFuturesArray =
                        connectionId2ChannelCache.asMap().values().stream()
                                .map(this::closeClientConnectionChannel)
                                .collect(Collectors.toList());
                StringTrackableCompletableFuture.<Channel>allOf(channelClosedFuturesArray.stream(),
                                () -> "all channels closed")
                        .handle((v, t) -> {
                                    if (t == null) {
                                        eventLoopFuture.future.complete(v);
                                    } else {
                                        eventLoopFuture.future.completeExceptionally(t);
                                    }
                                    return null;
                                },
                                () -> "Waiting for all channels to close: Remaining=" +
                                        (channelClosedFuturesArray.stream().filter(c -> !c.isDone()).count()));
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Caught error while closing cached connections").log();
                eventLoopFuture.future.completeExceptionally(e);
            }
        });
        return eventLoopFuture.map(f -> f.whenComplete((c, t) -> shutdownNow()),
                () -> "Final shutdown for " + this.getClass().getSimpleName());
    }

    public void closeConnection(IReplayContexts.IChannelKeyContext ctx) {
        var connId = ctx.getConnectionId();
        log.atInfo().setMessage(() -> "closing connection for " + connId).log();
        var channelsFuture = connectionId2ChannelCache.getIfPresent(connId);
        if (channelsFuture != null) {
            closeClientConnectionChannel(channelsFuture);
            connectionId2ChannelCache.invalidate(connId);
        } else {
            log.atTrace().setMessage(()->"No ChannelFuture for " + ctx +
                    " in closeConnection.  The connection may have already been closed").log();
        }
    }

    public Future<ConnectionReplaySession>
    submitEventualSessionGet(IReplayContexts.IChannelKeyContext ctx, boolean ignoreIfNotPresent) {
        ConnectionReplaySession channelFutureAndSchedule = getCachedSession(ctx, ignoreIfNotPresent);
        if (channelFutureAndSchedule == null) {
            var rval = new DefaultPromise<ConnectionReplaySession>(eventLoopGroup.next());
            rval.setSuccess(null);
            return rval;
        }
        return channelFutureAndSchedule.eventLoop.submit(() -> {
            if (channelFutureAndSchedule.getChannelFutureFuture() == null) {
                channelFutureAndSchedule.setChannelFutureFuture(
                        getResilientClientChannelProducer(channelFutureAndSchedule.eventLoop, ctx));
            }
            return channelFutureAndSchedule;
        });
    }

    @SneakyThrows
    public ConnectionReplaySession getCachedSession(IReplayContexts.IChannelKeyContext channelKeyCtx,
                                                    boolean dontCreate) {

        var crs = dontCreate ? connectionId2ChannelCache.getIfPresent(channelKeyCtx.getConnectionId()) :
                connectionId2ChannelCache.get(channelKeyCtx.getConnectionId(),
                        () -> buildConnectionReplaySession(channelKeyCtx));
        log.atTrace().setMessage(()->"returning ReplaySession=" + crs + " for " + channelKeyCtx.getConnectionId() +
                " from " + channelKeyCtx).log();
        return crs;
    }

    private DiagnosticTrackableCompletableFuture<String, Channel>
    closeClientConnectionChannel(ConnectionReplaySession channelAndFutureWork) {
        var channelClosedFuture =
                new StringTrackableCompletableFuture<Channel>(new CompletableFuture<>(),
                        ()->"Waiting for closeFuture() on channel");

        channelAndFutureWork.getChannelFutureFuture().map(cff->cff
                        .thenAccept(cf-> {
                            log.atTrace().setMessage(() -> "closing channel " + cf.channel() +
                                    "(" + channelAndFutureWork.getChannelKeyContext() + ")...").log();
                            cf.channel().close()
                                    .addListener(closeFuture -> {
                                        log.atTrace().setMessage(() -> "channel.close() has finished for " +
                                                channelAndFutureWork.getChannelKeyContext()).log();
                                        if (closeFuture.isSuccess()) {
                                            channelClosedFuture.future.complete(channelAndFutureWork.getInnerChannelFuture().channel());
                                        } else {
                                            channelClosedFuture.future.completeExceptionally(closeFuture.cause());
                                        }
                                        if (channelAndFutureWork.hasWorkRemaining()) {
                                            log.atWarn().setMessage(() ->
                                                    "Work items are still remaining for this connection session" +
                                                    "(last associated with connection=" +
                                                    channelAndFutureWork.getChannelKeyContext() +
                                                    ").  " + channelAndFutureWork.calculateSizeSlowly() +
                                                    " requests that were enqueued won't be run").log();
                                        }
                                        var schedule = channelAndFutureWork.schedule;
                                        while (channelAndFutureWork.hasWorkRemaining()) {
                                            var scheduledItemToKill = schedule.peekFirstItem();
                                            schedule.removeFirstItem();
                                        }
                                    });
                        })
                        .exceptionally(t->{
                            log.atWarn().setMessage(()->"client connection encountered an exception while closing")
                                    .setCause(t).log();
                            channelClosedFuture.future.completeExceptionally(t);
                            return null;
                        }),
                ()->"closing channel if its ChannelFuture was created");
        return channelClosedFuture;
    }
}
