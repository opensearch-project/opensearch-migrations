package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;

public class NettyScanningHttpProxy {
    @Getter
    protected final int proxyPort;
    protected Channel mainChannel;
    protected EventLoopGroup workerGroup;
    protected EventLoopGroup bossGroup;
    private final Consumer<Throwable> unstableProcessFailureHandler;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean unexpectedTerminationReported = new AtomicBoolean();

    public NettyScanningHttpProxy(
        int proxyPort,
        Consumer<Throwable> unstableProcessFailureHandler
    ) {
        this.proxyPort = proxyPort;
        this.unstableProcessFailureHandler = Objects.requireNonNull(unstableProcessFailureHandler);
    }

    public void start(ProxyChannelInitializer<?> proxyChannelInitializer, int numThreads)
        throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("captureProxyPoolBoss"));
        workerGroup = new NioEventLoopGroup(numThreads, new DefaultThreadFactory("captureProxyPoolWorker"));
        monitorEventLoops("boss", bossGroup);
        monitorEventLoops("worker", workerGroup);
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        try {
            mainChannel = serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(proxyChannelInitializer)
                .childOption(ChannelOption.AUTO_READ, false)
                .bind(proxyPort)
                .sync()
                .channel();
        } catch (Exception e) {
            stopping.set(true);
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            throw e;
        }
    }

    public void stop() throws InterruptedException {
        stopping.set(true);
        mainChannel.close();
        try {
            mainChannel.closeFuture().sync();
        } finally {
            var workerShutdown = workerGroup.shutdownGracefully();
            var bossShutdown = bossGroup.shutdownGracefully();
            workerShutdown.sync();
            bossShutdown.sync();
        }
    }

    public void waitForClose() throws InterruptedException {
        mainChannel.closeFuture().sync();
    }

    private void monitorEventLoops(String groupName, EventLoopGroup group) {
        int index = 0;
        for (var eventLoop : group) {
            var eventLoopName = groupName + "-" + index++;
            eventLoop.terminationFuture().addListener(future -> {
                if (stopping.get() || !unexpectedTerminationReported.compareAndSet(false, true)) {
                    return;
                }
                var failure = new IllegalStateException(
                    "Netty " + eventLoopName + " event loop terminated unexpectedly",
                    future.cause()
                );
                unstableProcessFailureHandler.accept(failure);
            });
        }
    }
}
