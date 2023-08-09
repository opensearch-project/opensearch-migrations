package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLEngine;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Slf4j
public class BacksideConnectionPool {
    private final URI backsideUri;
    private final SslContext backsideSslContext;
    private final FastThreadLocal channelClassToConnectionCacheForEachThread;
    private final Duration inactivityTimeout;
    private final int poolSize;

    public BacksideConnectionPool(URI backsideUri, SslContext backsideSslContext,
                                  int poolSize, Duration inactivityTimeout) {
        this.backsideUri = backsideUri;
        this.backsideSslContext = backsideSslContext;
        this.channelClassToConnectionCacheForEachThread = new FastThreadLocal();
        this.inactivityTimeout = inactivityTimeout;
        this.poolSize = poolSize;
    }

    public ChannelFuture getOutboundConnectionFuture(EventLoop eventLoop, Class channelClass) {
        if (poolSize == 0) {
            return buildConnectionFuture(eventLoop, channelClass);
        }
        return getExpiringWarmChannelPool(eventLoop, channelClass).getAvailableOrNewItem();
    }

    private ExpiringSubstitutableItemPool<ChannelFuture, Void>
    getExpiringWarmChannelPool(EventLoop eventLoop, Class channelClass) {
        var channelClassToChannelPoolMap = (HashMap<Class,ExpiringSubstitutableItemPool<ChannelFuture, Void>>)
                channelClassToConnectionCacheForEachThread.get();
        if (channelClassToChannelPoolMap == null) {
            channelClassToChannelPoolMap = new HashMap<>();
            channelClassToConnectionCacheForEachThread.set(channelClassToChannelPoolMap);
        }

        var thisContextsConnectionCache = channelClassToChannelPoolMap.get(channelClass);
        if (thisContextsConnectionCache == null) {
            thisContextsConnectionCache =
                    new ExpiringSubstitutableItemPool<ChannelFuture, Void>(inactivityTimeout,
                            eventLoop,
                            () -> buildConnectionFuture(eventLoop, channelClass),
                            x->x.channel().close(), poolSize, Duration.ZERO);
            channelClassToChannelPoolMap.put(channelClass, thisContextsConnectionCache);
            if (channelClassToChannelPoolMap.size() > 1) {
                log.warn("The connection pool for EventLoop=" + eventLoop + " has items for " +
                        channelClassToChannelPoolMap.size() + " different connection types.  " +
                        "Pools will co-exist indefinitely, " +
                        "possibly creating more connections and using more resources.");
                log.warn("Class types with disjoint pools for this event loop are " +
                        channelClassToChannelPoolMap.keySet().stream()
                                .map(c->c.getName())
                                .collect(Collectors.joining(", ")));
            }
        }

        return thisContextsConnectionCache;
    }

    private ChannelFuture buildConnectionFuture(EventLoop eventLoop, Class channelClass) {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        b.group(eventLoop)
                .channel(channelClass)
                .handler(new ChannelDuplexHandler())
                .option(ChannelOption.AUTO_READ, false);
        log.debug("Active - setting up backend connection");
        var f = b.connect(backsideUri.getHost(), backsideUri.getPort());
        var rval = new DefaultChannelPromise(f.channel());
        f.addListener((ChannelFutureListener) connectFuture -> {
            if (connectFuture.isSuccess()) {
                // connection complete start to read first data
                log.debug("Done setting up backend channel & it was successful");
                if (backsideSslContext != null) {
                    var pipeline = connectFuture.channel().pipeline();
                    SSLEngine sslEngine = backsideSslContext.newEngine(connectFuture.channel().alloc());
                    sslEngine.setUseClientMode(true);
                    var sslHandler =  new SslHandler(sslEngine);
                    pipeline.addFirst("ssl", sslHandler);
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
                rval.setFailure(connectFuture.cause());
            }
        });
        return rval;
    }
}
