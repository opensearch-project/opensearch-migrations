package org.opensearch.migrations.transform.shim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;

import org.opensearch.migrations.transform.shim.netty.MultiTargetRoutingHandler;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
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
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final String HTTPS_SCHEME = "https";
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();

    @Getter
    private final int port;
    private final Map<String, Target> targets;
    private final String primaryTarget;
    private final Set<String> activeTargets;
    private final List<ValidationRule> validators;
    private final java.util.function.Supplier<SSLEngine> sslEngineSupplier;
    private final SslContext backendSslContext;
    private final Duration secondaryTimeout;

    private Channel serverChannel;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

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
        this.port = port;
        this.targets = new LinkedHashMap<>(targets);
        this.primaryTarget = primaryTarget;
        this.activeTargets = activeTargets != null ? activeTargets : targets.keySet();
        this.validators = validators != null ? validators : List.of();
        this.sslEngineSupplier = sslEngineSupplier;
        this.secondaryTimeout = secondaryTimeout != null ? secondaryTimeout : DEFAULT_TIMEOUT;
        this.backendSslContext = buildBackendSslContext(allowInsecureBackend);

        if (!this.targets.containsKey(primaryTarget)) {
            throw new IllegalArgumentException("Primary target '" + primaryTarget + "' not in targets");
        }
        if (!this.targets.keySet().containsAll(this.activeTargets)) {
            throw new IllegalArgumentException("Active targets must be a subset of defined targets");
        }
    }

    /** Convenience constructor for testing — no TLS, default timeout. */
    public ShimProxy(
        int port,
        Map<String, Target> targets,
        String primaryTarget,
        List<ValidationRule> validators
    ) {
        this(port, targets, primaryTarget, null, validators, null, false, null);
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
            log.info("ShimProxy started on port {}, primary={}, targets={}, validators={}",
                port, primaryTarget, activeTargets, validators.size());
        } catch (Exception e) {
            shutdownEventLoopGroups();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
    }

    void initPipeline(ChannelPipeline pipeline) {
        if (sslEngineSupplier != null) {
            pipeline.addLast("ssl", new SslHandler(sslEngineSupplier.get()));
        }
        addLoggingHandler(pipeline, "A");

        pipeline.addLast("httpCodec", new HttpServerCodec());
        addLoggingHandler(pipeline, "B");

        pipeline.addLast("httpAggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
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
            targets, primaryTarget, activeTargets, validators, secondaryTimeout, backendSslContext));
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

    public void stop() throws InterruptedException {
        if (serverChannel != null) serverChannel.close().sync();
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        log.info("ShimProxy stopped");
    }

    public void waitForClose() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }
}
