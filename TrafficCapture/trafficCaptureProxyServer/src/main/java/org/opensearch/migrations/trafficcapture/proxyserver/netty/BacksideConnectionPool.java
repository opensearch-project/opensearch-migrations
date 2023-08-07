package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLEngine;
import java.net.URI;
import java.time.Duration;

@Slf4j
public class BacksideConnectionPool {
    private final URI backsideUri;
    private final SslContext backsideSslContext;

    public BacksideConnectionPool(URI backsideUri, SslContext backsideSslContext) {
        this.backsideUri = backsideUri;
        this.backsideSslContext = backsideSslContext;
    }

    public ChannelFuture getOutboundConnectionFuture(EventLoop eventLoop, Class channelClass) {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        b.group(eventLoop)
                .channel(channelClass)
                .handler(new ChannelDuplexHandler())
                .option(ChannelOption.AUTO_READ, false);
        log.debug("Active - setting up backend connection");
        var f = b.connect(backsideUri.getHost(), backsideUri.getPort());
        return f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // connection complete start to read first data
                log.debug("Done setting up backend channel & it was successful");
                if (backsideSslContext != null) {
                    var pipeline = future.channel().pipeline();
                    SSLEngine sslEngine = backsideSslContext.newEngine(future.channel().alloc());
                    sslEngine.setUseClientMode(true);
                    pipeline.addFirst("ssl", new SslHandler(sslEngine));
                }
            }
        });
    }
}
