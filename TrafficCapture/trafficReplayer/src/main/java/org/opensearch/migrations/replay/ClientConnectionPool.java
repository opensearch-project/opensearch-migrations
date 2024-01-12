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
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class ClientConnectionPool {
    private static final ContextKey<String> RECORD_ID_KEY = ContextKey.named("recordId");
    public static final String TELEMETRY_SCOPE_NAME = "ClientConnectionPool";
    public static final String TARGET_CONNECTION_POOL_NAME = "targetConnectionPool";
    private final URI serverUri;
    private final SslContext sslContext;
    public final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<String, ConnectionReplaySession> connectionId2ChannelCache;

    private final AtomicInteger numConnectionsCreated = new AtomicInteger(0);
    private final AtomicInteger numConnectionsClosed = new AtomicInteger(0);

    public ClientConnectionPool(URI serverUri, SslContext sslContext, int numThreads) {
        this.serverUri = serverUri;
        this.sslContext = sslContext;
        this.eventLoopGroup =
                new NioEventLoopGroup(numThreads, new DefaultThreadFactory(TARGET_CONNECTION_POOL_NAME));

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
            @Override
            public ConnectionReplaySession load(final String s) {
                if (eventLoopGroup.isShuttingDown()) {
                    throw new IllegalStateException("Event loop group is shutting down.  Not creating a new session.");
                }
                numConnectionsCreated.incrementAndGet();
                log.trace("creating connection session");
                // arguably the most only thing that matters here is associating this item with an
                // EventLoop (thread).  As the channel needs to be recycled, we'll come back to the
                // event loop that was tied to the original channel to bind all future channels to
                // the same event loop.  That means that we don't have to worry about concurrent
                // accesses/changes to the OTHER value that we're storing within the cache.
                return new ConnectionReplaySession(eventLoopGroup.next());
            }
        });
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

    public int getNumConnectionsCreated() {
        return numConnectionsCreated.get();
    }

    public int getNumConnectionsClosed() {
        return numConnectionsClosed.get();
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

    public void closeConnection(String connId) {
        log.atInfo().setMessage(() -> "closing connection for " + connId).log();
        var channelsFuture = connectionId2ChannelCache.getIfPresent(connId);
        if (channelsFuture != null) {
            closeClientConnectionChannel(channelsFuture);
            connectionId2ChannelCache.invalidate(connId);
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
    public ConnectionReplaySession getCachedSession(IReplayContexts.IChannelKeyContext channelKey, boolean dontCreate) {

        var crs = dontCreate ? connectionId2ChannelCache.getIfPresent(channelKey.getConnectionId()) :
                connectionId2ChannelCache.get(channelKey.getConnectionId());
        if (crs != null) {
            crs.setChannelContext(channelKey);
        }
        log.atTrace().setMessage(()->"returning ReplaySession=" + crs + " for " + channelKey.getConnectionId() +
                " from " + channelKey).log();
        return crs;
    }

    private DiagnosticTrackableCompletableFuture<String, Channel>
    closeClientConnectionChannel(ConnectionReplaySession channelAndFutureWork) {
        var channelClosedFuture =
                new StringTrackableCompletableFuture<Channel>(new CompletableFuture<>(),
                        ()->"Waiting for closeFuture() on channel");

        numConnectionsClosed.incrementAndGet();
        channelAndFutureWork.getChannelFutureFuture().map(cff->cff
                        .thenAccept(cf-> {
                            cf.channel().close()
                                    .addListener(closeFuture -> {
                                        channelAndFutureWork.getChannelContext().onConnectionClosed();
                                        if (closeFuture.isSuccess()) {
                                            channelClosedFuture.future.complete(channelAndFutureWork.getInnerChannelFuture().channel());
                                        } else {
                                            channelClosedFuture.future.completeExceptionally(closeFuture.cause());
                                        }
                                    });
                            if (channelAndFutureWork.hasWorkRemaining()) {
                                log.atWarn().setMessage(()->"Work items are still remaining for this connection session" +
                                        "(last associated with connection=" +
                                        channelAndFutureWork.getChannelContext() +
                                        ").  " + channelAndFutureWork.calculateSizeSlowly() +
                                        " requests that were enqueued won't be run").log();
                            }
                            var schedule = channelAndFutureWork.schedule;
                            while (channelAndFutureWork.hasWorkRemaining()) {
                                var scheduledItemToKill = schedule.peekFirstItem();
                                schedule.removeFirstItem();
                            }
                        })
                        .exceptionally(t->{channelClosedFuture.future.completeExceptionally(t);return null;}),
                ()->"closing channel if its ChannelFuture was created");
        return channelClosedFuture;
    }
}
