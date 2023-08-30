package org.opensearch.migrations.replay;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class ClientConnectionPool {
    public final NioEventLoopGroup eventLoopGroup;
    private final LoadingCache<String, ChannelFuture> connectionId2ChannelCache;

    private final AtomicInteger numConnectionsCreated = new AtomicInteger(0);
    private final AtomicInteger numConnectionsClosed = new AtomicInteger(0);

    public ClientConnectionPool(URI serverUri,
                                SslContext sslContext,
                                int numThreads) {
        this.eventLoopGroup = new NioEventLoopGroup(numThreads);

        connectionId2ChannelCache = CacheBuilder.newBuilder().build(new CacheLoader<>() {
            @Override
            public ChannelFuture load(final String s) {
                numConnectionsCreated.incrementAndGet();
                log.trace("creating connection future");
                return NettyPacketToHttpConsumer.createClientConnection(eventLoopGroup, sslContext, serverUri, s);
            }
        });
    }

    public int getNumConnectionsCreated() {
        return numConnectionsCreated.get();
    }

    public int getNumConnectionsClosed() {
        return numConnectionsClosed.get();
    }

    public DiagnosticTrackableCompletableFuture<String,Void> stopGroup() {
        var eventLoopFuture =
                new StringTrackableCompletableFuture<Void>(new CompletableFuture<>(),
                        () -> "all channels closed");
        this.eventLoopGroup.submit(()-> {
            try {
                var channelClosedFuturesArray =
                        connectionId2ChannelCache.asMap().values().stream()
                                .map(this::closeClientConnectionChannel)
                                .collect(Collectors.toList());
                StringTrackableCompletableFuture.<Channel>allOf(channelClosedFuturesArray.stream(),
                                () -> "all channels closed")
                        .handle((v,t)-> {
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
        return eventLoopFuture.map(f->f.whenComplete((c,t)->{
            connectionId2ChannelCache.invalidateAll();
            eventLoopGroup.shutdownGracefully();
        }), () -> "Final shutdown for " + this.getClass().getSimpleName());
    }

    public void closeConnection(String connId) {
        log.atDebug().setMessage(()->"closing connection for " + connId).log();
        var channelsFuture = connectionId2ChannelCache.getIfPresent(connId);
        if (channelsFuture != null) {
            closeClientConnectionChannel(channelsFuture);
            connectionId2ChannelCache.invalidate(connId);
        }
    }

    ChannelFuture get(UniqueRequestKey requestKey, int timesToRetry) {
        log.trace("loading NettyHandler");

        ChannelFuture channelFuture = null;
        try {
            channelFuture = connectionId2ChannelCache.get(requestKey.connectionId);
        } catch (ExecutionException e) {
            if (timesToRetry <= 0) {
                throw new RuntimeException(e);
            }
        }
        if (channelFuture == null || (channelFuture.isDone() && !channelFuture.channel().isActive())) {
            connectionId2ChannelCache.invalidate(requestKey.connectionId);
            if (timesToRetry > 0) {
                return get(requestKey, timesToRetry - 1);
            } else {
                throw new RuntimeException("Channel wasn't active and out of retries.");
            }
        }

        return channelFuture;
    }

    private DiagnosticTrackableCompletableFuture<String, Channel>
    closeClientConnectionChannel(ChannelFuture channelsFuture) {
        var channelClosedFuture =
                new StringTrackableCompletableFuture<>(new CompletableFuture<Channel>(),
                        ()->"Waiting for closeFuture() on channel");

        numConnectionsClosed.incrementAndGet();
        var cf = channelsFuture.channel().close();
        cf.addListener(closeFuture -> {
            if (closeFuture.isSuccess()) {
                channelClosedFuture.future.complete(channelsFuture.channel());
            } else {
                channelClosedFuture.future.completeExceptionally(closeFuture.cause());
            }
        });
        return channelClosedFuture;
    }

    public void invalidate(String connectionId) {
        connectionId2ChannelCache.invalidate(connectionId);
    }
}
