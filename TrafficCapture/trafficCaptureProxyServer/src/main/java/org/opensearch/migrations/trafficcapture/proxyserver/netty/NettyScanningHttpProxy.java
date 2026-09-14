package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.Getter;

public class NettyScanningHttpProxy {
    @Getter
    protected final int proxyPort;
    protected Channel mainChannel;
    protected EventLoopGroup workerGroup;
    protected EventLoopGroup bossGroup;
    private final Consumer<Throwable> unstableProcessFailureHandler;
    private final DefaultChannelGroup activeConnections =
        new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
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
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) throws Exception {
                        activeConnections.add(channel);
                        proxyChannelInitializer.initChannel(channel);
                    }
                })
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
        stopAcceptingNewConnections();
        disconnectActiveConnections().join();
        stopEventLoops();
    }

    public void stopAcceptingNewConnections() throws InterruptedException {
        stopping.set(true);
        if (mainChannel == null) {
            return;
        }
        mainChannel.close();
        mainChannel.closeFuture().sync();
    }

    public CompletableFuture<Void> whenNoActiveConnections() {
        if (activeConnections.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var result = new CompletableFuture<Void>();
        activeConnections.newCloseFuture().addListener(future -> {
            if (future.isSuccess()) {
                result.complete(null);
            } else {
                result.completeExceptionally(future.cause());
            }
        });
        return result;
    }

    public CompletableFuture<Void> disconnectActiveConnections() {
        var result = new CompletableFuture<Void>();
        activeConnections.close().addListener(future -> {
            if (future.isSuccess()) {
                result.complete(null);
            } else {
                result.completeExceptionally(future.cause());
            }
        });
        return result;
    }

    public int activeConnectionCount() {
        return activeConnections.size();
    }

    public void stopEventLoops() throws InterruptedException {
        stopping.set(true);
        try {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().sync();
            }
        } finally {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().sync();
            }
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
