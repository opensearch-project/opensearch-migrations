package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;

import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.NonNull;

public class NettyScanningHttpProxy {
    protected final int proxyPort;
    protected Channel mainChannel;
    protected EventLoopGroup workerGroup;
    protected EventLoopGroup bossGroup;

    public NettyScanningHttpProxy(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void start(
        IRootWireLoggingContext rootContext,
        BacksideConnectionPool backsideConnectionPool,
        int numThreads,
        Supplier<SSLEngine> sslEngineSupplier,
        IConnectionCaptureFactory<Object> connectionCaptureFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate
    ) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("captureProxyPoolBoss"));
        workerGroup = new NioEventLoopGroup(numThreads, new DefaultThreadFactory("captureProxyPoolWorker"));
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        try {
            mainChannel = serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(
                    new ProxyChannelInitializer<>(
                        rootContext,
                        backsideConnectionPool,
                        sslEngineSupplier,
                        connectionCaptureFactory,
                        requestCapturePredicate
                    )
                )
                .childOption(ChannelOption.AUTO_READ, false)
                .bind(proxyPort)
                .sync()
                .channel();
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

    public void waitForClose() throws InterruptedException {
        mainChannel.closeFuture().sync();
    }
}
