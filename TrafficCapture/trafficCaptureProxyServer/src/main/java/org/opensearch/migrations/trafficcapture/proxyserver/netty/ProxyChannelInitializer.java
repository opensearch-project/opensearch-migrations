package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import java.util.function.Supplier;

public class ProxyChannelInitializer extends ChannelInitializer<SocketChannel> {

    Supplier<SSLEngine> sslEngineProvider;
    private final String host;
    private final int port;

    public ProxyChannelInitializer(String host, int port) {
        this(host, port, () -> null);
    }

    public ProxyChannelInitializer(String host, int port, Supplier<SSLEngine> sslEngineSupplier) {
        this.host = host;
        this.port = port;
        this.sslEngineProvider = sslEngineSupplier;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        var sslContext = sslEngineProvider.get();
        if (sslContext != null) {
            ch.pipeline().addLast(new SslHandler(sslEngineProvider.get()));
        }

        ch.pipeline().addLast(
                new FrontsideHandler(host, port));
    }
}
