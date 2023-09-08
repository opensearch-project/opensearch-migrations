package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.slf4j.event.Level;
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
import java.util.concurrent.TimeUnit;

@Slf4j
public class BacksideConnectionPool {
    private final URI backsideUri;
    private final SslContext backsideSslContext;
    private final FastThreadLocal connectionCacheForEachThread;
    private final Duration inactivityTimeout;
    private final int poolSize;

    public BacksideConnectionPool(URI backsideUri, SslContext backsideSslContext,
                                  int poolSize, Duration inactivityTimeout) {
        this.backsideUri = backsideUri;
        this.backsideSslContext = backsideSslContext;
        this.connectionCacheForEachThread = new FastThreadLocal();
        this.inactivityTimeout = inactivityTimeout;
        this.poolSize = poolSize;
    }

    public ChannelFuture getOutboundConnectionFuture(EventLoop eventLoop) {
        if (poolSize == 0) {
            return buildConnectionFuture(eventLoop);
        }
        return getExpiringWarmChannelPool(eventLoop).getAvailableOrNewItem();
    }

    private ExpiringSubstitutableItemPool<ChannelFuture, Void>
    getExpiringWarmChannelPool(EventLoop eventLoop) {
        var thisContextsConnectionCache = (ExpiringSubstitutableItemPool<ChannelFuture, Void>)
                connectionCacheForEachThread.get();
        if (thisContextsConnectionCache == null) {
            thisContextsConnectionCache =
                    new ExpiringSubstitutableItemPool<ChannelFuture, Void>(inactivityTimeout,
                            eventLoop,
                            () -> buildConnectionFuture(eventLoop),
                            x->x.channel().close(), poolSize, Duration.ZERO);
            if (log.isInfoEnabled()) {
                logProgressAtInterval(Level.INFO, eventLoop,
                        thisContextsConnectionCache, Duration.ofSeconds(30));
            }
            connectionCacheForEachThread.set(thisContextsConnectionCache);
        }

        return thisContextsConnectionCache;
    }

    private void logProgressAtInterval(Level logLevel, EventLoop eventLoop,
                                       ExpiringSubstitutableItemPool<ChannelFuture, Void> channelPoolMap,
                                       Duration frequency) {
        eventLoop.schedule(() -> {
            log.atLevel(logLevel).log(channelPoolMap.getStats().toString());
            logProgressAtInterval(logLevel, eventLoop, channelPoolMap, frequency);
        }, frequency.toMillis(), TimeUnit.MILLISECONDS);
    }

    private ChannelFuture buildConnectionFuture(EventLoop eventLoop) {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        b.group(eventLoop)
                .channel(NioSocketChannel.class)
                .handler(new ChannelDuplexHandler())
                .option(ChannelOption.AUTO_READ, false);
        var f = b.connect(backsideUri.getHost(), backsideUri.getPort());
        var rval = new DefaultChannelPromise(f.channel());
        f.addListener((ChannelFutureListener) connectFuture -> {
            if (connectFuture.isSuccess()) {
                // connection complete start to read first data
                log.debug("Done setting up backend channel & it was successful (" + connectFuture.channel() + ")");
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
