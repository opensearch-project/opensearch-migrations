package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;

import javax.net.ssl.SSLEngine;
import java.net.URI;
import java.util.function.Supplier;

public class NettyScanningHttpProxy {
    private final int proxyPort;
    private Channel mainChannel;
    private EventLoopGroup workerGroup;
    private EventLoopGroup bossGroup;

    public NettyScanningHttpProxy(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void start(BacksideConnectionPool backsideConnectionPool,
                      int numThreads,
                      Supplier<SSLEngine> sslEngineSupplier,
                      IConnectionCaptureFactory connectionCaptureFactory) throws InterruptedException {
        InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
        bossGroup = new NioEventLoopGroup(numThreads);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        try {
            mainChannel = serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ProxyChannelInitializer(backsideConnectionPool, sslEngineSupplier,
                            connectionCaptureFactory))
                    .childOption(ChannelOption.AUTO_READ, false)
                    .bind(proxyPort).sync().channel();
        } catch (Exception e) {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            throw e;
        }
    }

    public void stop() throws InterruptedException {
        mainChannel.close();
        try {
            mainChannel.closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
