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
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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

    public ClientConnectionPool(URI serverUri, SslContext sslContext, String targetConnectionPoolName, int numThreads) {
        this.serverUri = serverUri;
        this.sslContext = sslContext;
        this.eventLoopGroup =
                new NioEventLoopGroup(numThreads, new DefaultThreadFactory(targetConnectionPoolName));

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(CacheLoader.from(key -> {
            throw new UnsupportedOperationException("Use Cache.get(key, callable) instead");
        }));
    }

    public ConnectionReplaySession buildConnectionReplaySession(final IReplayContexts.IChannelKeyContext channelKeyCtx) {
        if (eventLoopGroup.isShuttingDown()) {
            throw new IllegalStateException("Event loop group is shutting down.  Not creating a new session.");
        }
        // arguably the most only thing that matters here is associating this item with an
        // EventLoop (thread).  As the channel needs to be recycled, we'll come back to the
        // event loop that was tied to the original channel to bind all future channels to
        // the same event loop.  That means that we don't have to worry about concurrent
        // accesses/changes to the OTHER value that we're storing within the cache.
        var eventLoop = eventLoopGroup.next();
        return new ConnectionReplaySession(eventLoop, channelKeyCtx,
                ()->getResilientClientChannelProducer(eventLoop, channelKeyCtx));
    }

    private DiagnosticTrackableCompletableFuture<String, ChannelFuture>
    getResilientClientChannelProducer(EventLoop eventLoop, IReplayContexts.IChannelKeyContext connectionContext) {
        return new AdaptiveRateLimiter<String, ChannelFuture>()
                .get(() -> {
                    var channelFuture = NettyPacketToHttpConsumer.createClientConnection(eventLoop,
                            sslContext, serverUri, connectionContext);
                    return getCompletedChannelFutureAsCompletableFuture(connectionContext, channelFuture);
                });
    }

    public static StringTrackableCompletableFuture<ChannelFuture>
    getCompletedChannelFutureAsCompletableFuture(IReplayContexts.IChannelKeyContext connectionContext,
                                                 ChannelFuture channelFuture) {
        var clientConnectionChannelCreatedFuture =
                new StringTrackableCompletableFuture<ChannelFuture>(new CompletableFuture<>(),
                        () -> "waiting for createClientConnection to finish");
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
    }

    public CompletableFuture<Void> shutdownNow() {
        CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();
        connectionId2ChannelCache.invalidateAll();
        eventLoopGroup.shutdownGracefully().addListener(f->{
            if (f.isSuccess()) {
                shutdownFuture.complete(null);
            } else {
                shutdownFuture.completeExceptionally(f.cause());
            }
        });
        return shutdownFuture;
    }
    
    public void closeConnection(IReplayContexts.IChannelKeyContext ctx, int sessionNumber) {
        var connId = ctx.getConnectionId();
        log.atInfo().setMessage(() -> "closing connection for " + connId).log();
        var connectionReplaySession = connectionId2ChannelCache.getIfPresent(getKey(connId, sessionNumber));
        if (connectionReplaySession != null) {
            closeClientConnectionChannel(connectionReplaySession);
            connectionId2ChannelCache.invalidate(connId);
        } else {
            log.atInfo().setMessage(()->"No ChannelFuture for " + ctx +
                    " in closeConnection.  The connection may have already been closed").log();
        }
    }

    @SneakyThrows
    public @NonNull ConnectionReplaySession getCachedSession(IReplayContexts.IChannelKeyContext channelKeyCtx,
                                                             int sessionNumber) {
        var crs = connectionId2ChannelCache.get(getKey(channelKeyCtx.getConnectionId(), sessionNumber),
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

        channelAndFutureWork.getFutureThatReturnsChannelFuture(false)
                .thenAccept(channelFuture-> {
                    if (channelFuture == null) {
                        return;
                    }
                    log.atTrace().setMessage(() -> "closing channel " + channelFuture.channel() +
                            "(" + channelAndFutureWork.getChannelKeyContext() + ")...").log();
                    channelFuture.channel().close()
                            .addListener(closeFuture -> {
                                log.atTrace().setMessage(() -> "channel.close() has finished for " +
                                        channelAndFutureWork.getChannelKeyContext()).log();
                                if (closeFuture.isSuccess()) {
                                    channelClosedFuture.future.complete(channelFuture.channel());
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
                                while (channelAndFutureWork.schedule.hasPendingTransmissions()) {
                                    var scheduledItemToKill = schedule.peekFirstItem();
                                    schedule.removeFirstItem();
                                }
                            });
                }, () -> "calling channel.close()")
                .exceptionally(t->{
                    log.atWarn().setMessage(()->"client connection encountered an exception while closing")
                            .setCause(t).log();
                    channelClosedFuture.future.completeExceptionally(t);
                    return null;
                }, () -> "handling any potential exceptions");
        return channelClosedFuture;
    }
}
