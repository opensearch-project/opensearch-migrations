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
import io.netty.util.concurrent.Future;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class ClientConnectionPool {

    private final URI serverUri;
    private final SslContext sslContext;
    public final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<String, ConnectionReplaySession> connectionId2ChannelCache;

    private final AtomicInteger numConnectionsCreated = new AtomicInteger(0);
    private final AtomicInteger numConnectionsClosed = new AtomicInteger(0);

    public ClientConnectionPool(URI serverUri, SslContext sslContext, int numThreads) {
        this.serverUri = serverUri;
        this.sslContext = sslContext;
        this.eventLoopGroup = new NioEventLoopGroup(numThreads);

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
            @Override
            public ConnectionReplaySession load(final String s) {
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
    getResilientClientChannelProducer(EventLoop eventLoop, String s) {
        return new AdaptiveRateLimiter<String, ChannelFuture>()
                .get(() -> {
                    var clientConnectionChannelCreatedFuture =
                            new StringTrackableCompletableFuture<ChannelFuture>(new CompletableFuture<>(),
                                    () -> "waiting for createClientConnection to finish");
                    var channelFuture =
                            NettyPacketToHttpConsumer.createClientConnection(eventLoop, sslContext, serverUri, s);
                    channelFuture.addListener(f -> {
                        log.atTrace().setMessage(()->s + " ChannelFuture result for create=" + f.isSuccess()).log();
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

    public DiagnosticTrackableCompletableFuture<String, Void> stopGroup() {
        var eventLoopFuture =
                new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                        () -> "all channels closed");
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
                log.error("bad", e);
                eventLoopFuture.future.completeExceptionally(e);
            }
        });
        return eventLoopFuture.map(f -> f.whenComplete((c, t) -> {
            connectionId2ChannelCache.invalidateAll();
            eventLoopGroup.shutdownGracefully();
        }), () -> "Final shutdown for " + this.getClass().getSimpleName());
    }

    public void closeConnection(String connId) {
        log.atDebug().setMessage(() -> "closing connection for " + connId).log();
        var channelsFuture = connectionId2ChannelCache.getIfPresent(connId);
        if (channelsFuture != null) {
            closeClientConnectionChannel(channelsFuture);
            connectionId2ChannelCache.invalidate(connId);
        }
    }

    public Future<ConnectionReplaySession>
    submitEventualChannelGet(UniqueRequestKey requestKey, boolean ignoreIfNotPresent) {
        ConnectionReplaySession channelFutureAndSchedule =
                getCachedSession(requestKey, ignoreIfNotPresent);
        if (channelFutureAndSchedule == null) {
            var rval = new DefaultPromise<ConnectionReplaySession>(eventLoopGroup.next());
            rval.setSuccess(null);
            return rval;
        }
        return channelFutureAndSchedule.eventLoop.submit(() -> {
            if (channelFutureAndSchedule.channelFutureFuture == null) {
                channelFutureAndSchedule.channelFutureFuture =
                        getResilientClientChannelProducer(channelFutureAndSchedule.eventLoop, requestKey.connectionId);
            }
            return channelFutureAndSchedule;
        });
    }

    @SneakyThrows
    public ConnectionReplaySession getCachedSession(UniqueRequestKey requestKey, boolean dontCreate) {
        return dontCreate ? connectionId2ChannelCache.getIfPresent(requestKey.connectionId) :
                connectionId2ChannelCache.get(requestKey.connectionId);
    }

    private DiagnosticTrackableCompletableFuture<String, Channel>
    closeClientConnectionChannel(ConnectionReplaySession channelAndFutureWork) {
        var channelClosedFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<Channel>(),
                        ()->"Waiting for closeFuture() on channel");

        numConnectionsClosed.incrementAndGet();
        channelAndFutureWork.channelFutureFuture.map(cff->cff
                        .thenAccept(cf-> {
                            cf.channel().close()
                                    .addListener(closeFuture -> {
                                        if (closeFuture.isSuccess()) {
                                            channelClosedFuture.future.complete(channelAndFutureWork.getInnerChannelFuture().channel());
                                        } else {
                                            channelClosedFuture.future.completeExceptionally(closeFuture.cause());
                                        }
                                    });
                            if (!channelAndFutureWork.hasWorkRemaining()) {
                                log.atWarn().setMessage(()->"Work items are still remaining.  "
                                        + channelAndFutureWork.calculateSizeSlowly() + " " +
                                        "requests that were enqueued won't be run").log();
                            }
                        })
                        .exceptionally(t->{channelClosedFuture.future.completeExceptionally(t);return null;}),
                ()->"closing channel if its ChannelFuture was created");
        return channelClosedFuture;
    }
}
