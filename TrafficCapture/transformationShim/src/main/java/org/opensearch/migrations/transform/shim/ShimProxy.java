package org.opensearch.migrations.transform.shim;

import javax.net.ssl.SSLEngine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.transform.shim.netty.MultiTargetRoutingHandler;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Multi-target proxy that dispatches requests to N named targets in parallel,
 * collects responses, runs validators, and returns the primary target's response
 * with per-target and validation headers.
 * <p>
 * Supports single-target passthrough (1 target, no validators) through
 * multi-target validation (N targets with validators).
 */
@Slf4j
public class ShimProxy {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final String HTTPS_SCHEME = "https";
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    @Getter
    private final int port;
    private final Map<String, Target> targets;
    private final String primaryTarget;
    private final Set<String> activeTargets;
    private final List<ValidationRule> validators;
    private final java.util.function.Supplier<SSLEngine> sslEngineSupplier;
    private final SslContext backendSslContext;
    private final Duration secondaryTimeout;
    private final int maxContentLength;

    private Channel serverChannel;
    private Channel healthChannel;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public ShimProxy(
        int port,
        Map<String, Target> targets,
        String primaryTarget,
        Set<String> activeTargets,
        List<ValidationRule> validators,
        java.util.function.Supplier<SSLEngine> sslEngineSupplier,
        boolean allowInsecureBackend,
        Duration secondaryTimeout,
        int maxContentLength
    ) {
        this.port = port;
        this.targets = new LinkedHashMap<>(targets);
        this.primaryTarget = primaryTarget;
        this.activeTargets = activeTargets != null ? activeTargets : targets.keySet();
        this.validators = validators != null ? validators : List.of();
        this.sslEngineSupplier = sslEngineSupplier;
        this.secondaryTimeout = secondaryTimeout != null ? secondaryTimeout : DEFAULT_TIMEOUT;
        this.maxContentLength = maxContentLength > 0 ? maxContentLength : DEFAULT_MAX_CONTENT_LENGTH;
        this.backendSslContext = buildBackendSslContext(allowInsecureBackend);

        if (!this.targets.containsKey(primaryTarget)) {
            throw new IllegalArgumentException("Primary target '" + primaryTarget + "' not in targets");
        }
        if (!this.targets.keySet().containsAll(this.activeTargets)) {
            throw new IllegalArgumentException("Active targets must be a subset of defined targets");
        }
    }

    /** Convenience constructor for testing — no TLS, default timeout, default max content length. */
    public ShimProxy(
        int port,
        Map<String, Target> targets,
        String primaryTarget,
        List<ValidationRule> validators
    ) {
        this(port, targets, primaryTarget, null, validators, null, false, null, DEFAULT_MAX_CONTENT_LENGTH);
    }

    /** Convenience constructor — no max content length override. */
    public ShimProxy(
        int port,
        Map<String, Target> targets,
        String primaryTarget,
        Set<String> activeTargets,
        List<ValidationRule> validators,
        java.util.function.Supplier<SSLEngine> sslEngineSupplier,
        boolean allowInsecureBackend,
        Duration secondaryTimeout
    ) {
        this(port, targets, primaryTarget, activeTargets, validators, sslEngineSupplier,
            allowInsecureBackend, secondaryTimeout, DEFAULT_MAX_CONTENT_LENGTH);
    }

    private SslContext buildBackendSslContext(boolean allowInsecure) {
        boolean anyHttps = targets.values().stream()
            .anyMatch(t -> HTTPS_SCHEME.equalsIgnoreCase(t.uri().getScheme()));
        if (!anyHttps) return null;
        try {
            var builder = SslContextBuilder.forClient();
            if (allowInsecure) builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create backend SSL context", e);
        }
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("validationBoss"));
        workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("validationWorker"));

        var bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    initPipeline(ch.pipeline());
                }
            })
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("ShimProxy started on port {}, primary={}, targets={}, validators={}, maxContentLength={}",
                port, primaryTarget, activeTargets, validators.size(), maxContentLength);
        } catch (Exception e) {
            shutdownEventLoopGroups();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Start a health check HTTP server on a separate port. Returns 200 for GET /health. */
    public void startHealthServer(int healthPort) throws InterruptedException {
        var bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast("httpCodec", new HttpServerCodec())
                        .addLast("httpAggregator", new HttpObjectAggregator(1024))
                        .addLast("healthHandler", new HealthCheckHandler());
                }
            });
        healthChannel = bootstrap.bind(healthPort).sync().channel();
        log.info("Health check server started on port {}", healthPort);
    }

    void initPipeline(ChannelPipeline pipeline) {
        if (sslEngineSupplier != null) {
            pipeline.addLast("ssl", new SslHandler(sslEngineSupplier.get()));
        }
        addLoggingHandler(pipeline, "A");

        pipeline.addLast("httpCodec", new HttpServerCodec());
        addLoggingHandler(pipeline, "B");

        pipeline.addLast("httpAggregator", new HttpObjectAggregator(maxContentLength));
        addLoggingHandler(pipeline, "C");

        // Keep-alive detection
        pipeline.addLast("keepAliveDetect", new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
                    ctx.channel().attr(
                        org.opensearch.migrations.transform.shim.netty.ShimChannelAttributes.KEEP_ALIVE
                    ).set(HttpUtil.isKeepAlive((io.netty.handler.codec.http.HttpRequest) msg));
                }
                ctx.fireChannelRead(msg);
            }
        });
        addLoggingHandler(pipeline, "D");

        pipeline.addLast("multiTargetRouter", new MultiTargetRoutingHandler(
            targets, primaryTarget, activeTargets, validators, secondaryTimeout,
            backendSslContext, maxContentLength, activeRequests));
        addLoggingHandler(pipeline, "E");
    }

    private static void addLoggingHandler(ChannelPipeline pipeline, String name) {
        PIPELINE_LOGGING_OPTIONAL.ifPresent(
            logLevel -> pipeline.addLast(new LoggingHandler("v" + name, logLevel)));
    }

    private void shutdownEventLoopGroups() {
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    /**
     * Graceful shutdown: stop accepting new connections, drain in-flight requests,
     * then shut down event loops.
     */
    public void stop() throws InterruptedException {
        // Stop accepting new connections
        if (healthChannel != null) healthChannel.close().sync();
        if (serverChannel != null) serverChannel.close().sync();

        // Drain in-flight requests
        long drainDeadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (activeRequests.get() > 0 && System.nanoTime() < drainDeadline) {
            log.info("Draining {} in-flight requests...", activeRequests.get());
            Thread.sleep(100);
        }
        if (activeRequests.get() > 0) {
            log.warn("Drain timeout reached with {} in-flight requests, forcing shutdown", activeRequests.get());
        }

        if (workerGroup != null) workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        log.info("ShimProxy stopped");
    }

    public void waitForClose() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }

    /** Simple health check handler — returns 200 OK for any request. */
    static class HealthCheckHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
