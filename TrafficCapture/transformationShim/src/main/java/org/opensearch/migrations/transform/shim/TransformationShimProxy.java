package org.opensearch.migrations.transform.shim;

import javax.net.ssl.SSLEngine;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.shim.netty.BackendForwardingHandler;
import org.opensearch.migrations.transform.shim.netty.RequestTransformHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
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
 * A transforming proxy that accepts HTTP requests, transforms them via IJsonTransformer,
 * optionally signs them with SigV4, forwards to a backend via Netty, transforms the response,
 * and returns it to the client.
 *
 * <p>Pipeline structure follows the same alphabetical logging convention as the replayer's
 * RequestPipelineOrchestrator and the proxy's ProxyChannelInitializer:</p>
 *
 * <pre>
 * [A] SslHandler (optional, frontend TLS)
 * [B] HttpServerCodec
 * [C] HttpObjectAggregator
 * [D] RequestTransformHandler — applies IJsonTransformer to the request
 * [E] Auth handler (optional) — SigV4, basic auth, or custom
 * [F] BackendForwardingHandler — forwards to backend via Netty, transforms response
 * </pre>
 */
@Slf4j
public class TransformationShimProxy {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(150);
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 100;
    public static final String HTTPS_SCHEME = "https";
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    /**
     * Set this to of(LogLevel.ERROR) or whatever level you'd like to get logging between each handler.
     * Set this to Optional.empty() to disable intra-handler logging.
     * Same pattern as RequestPipelineOrchestrator.PIPELINE_LOGGING_OPTIONAL.
     */
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();

    @Getter
    private final int port;
    private final URI backendUri;
    private final IJsonTransformer requestTransformer;
    private final IJsonTransformer responseTransformer;
    private final Supplier<SSLEngine> sslEngineSupplier;
    private final SslContext backendSslContext;
    private final Duration timeout;
    private final int maxConcurrentRequests;
    private final Supplier<ChannelHandler> authHandlerSupplier;

    private Channel serverChannel;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Semaphore concurrencySemaphore;

    /**
     * Full constructor with all production options.
     */
    public TransformationShimProxy(
        int port,
        URI backendUri,
        IJsonTransformer requestTransformer,
        IJsonTransformer responseTransformer,
        Supplier<SSLEngine> sslEngineSupplier,
        boolean allowInsecureBackend,
        Duration timeout,
        int maxConcurrentRequests,
        Supplier<ChannelHandler> authHandlerSupplier
    ) {
        this.port = port;
        this.backendUri = backendUri;
        this.requestTransformer = requestTransformer;
        this.responseTransformer = responseTransformer;
        this.sslEngineSupplier = sslEngineSupplier;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.maxConcurrentRequests = maxConcurrentRequests > 0 ? maxConcurrentRequests : DEFAULT_MAX_CONCURRENT_REQUESTS;
        this.concurrencySemaphore = new Semaphore(this.maxConcurrentRequests);
        this.backendSslContext = buildBackendSslContext(allowInsecureBackend);
        this.authHandlerSupplier = authHandlerSupplier;
    }

    /** Convenience constructor for testing — no TLS, no auth, default timeout. */
    public TransformationShimProxy(int port, URI backendUri,
                                   IJsonTransformer requestTransformer,
                                   IJsonTransformer responseTransformer) {
        this.port = port;
        this.backendUri = backendUri;
        this.requestTransformer = requestTransformer;
        this.responseTransformer = responseTransformer;
        this.sslEngineSupplier = null;
        this.timeout = DEFAULT_TIMEOUT;
        this.maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
        this.concurrencySemaphore = new Semaphore(this.maxConcurrentRequests);
        this.backendSslContext = buildBackendSslContext(false);
        this.authHandlerSupplier = null;
    }

    private SslContext buildBackendSslContext(boolean allowInsecure) {
        if (!HTTPS_SCHEME.equalsIgnoreCase(backendUri.getScheme())) return null;
        try {
            var builder = SslContextBuilder.forClient();
            if (allowInsecure) {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create backend SSL context", e);
        }
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("shimBoss"));
        workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("shimWorker"));

        var bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    initFrontendPipeline(ch.pipeline());
                }
            })
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("TransformationShimProxy started on port {}, backend={}, timeout={}s, "
                    + "maxConcurrent={}, frontTLS={}, backTLS={}, auth={}",
                port, backendUri, timeout.getSeconds(), maxConcurrentRequests,
                sslEngineSupplier != null,
                HTTPS_SCHEME.equalsIgnoreCase(backendUri.getScheme()),
                authHandlerSupplier != null);
        } catch (Exception e) {
            shutdownEventLoopGroups();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    /**
     * Initialize the frontend pipeline with alphabetical logging handlers between stages.
     * Follows the same convention as RequestPipelineOrchestrator and NettyPacketToHttpConsumer.
     */
    void initFrontendPipeline(ChannelPipeline pipeline) {
        // [A] Optional TLS termination
        if (sslEngineSupplier != null) {
            pipeline.addLast("ssl", new SslHandler(sslEngineSupplier.get()));
        }
        addLoggingHandler(pipeline, "A");

        // [B] HTTP codec — decodes inbound HTTP requests
        pipeline.addLast("httpCodec", new HttpServerCodec());
        addLoggingHandler(pipeline, "B");

        // [C] Aggregator — assembles FullHttpRequest from codec output
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));
        addLoggingHandler(pipeline, "C");

        // [D] Request transform — applies IJsonTransformer to the request
        pipeline.addLast("requestTransform", new RequestTransformHandler(requestTransformer));
        addLoggingHandler(pipeline, "D");

        // [E] Optional auth signing (SigV4, basic auth, or custom)
        if (authHandlerSupplier != null) {
            pipeline.addLast("authSigner", authHandlerSupplier.get());
            addLoggingHandler(pipeline, "E");
        }

        // [F] Backend forwarding — connects to backend via Netty, transforms response, relays back
        pipeline.addLast("backendForwarder", new BackendForwardingHandler(
            backendUri, responseTransformer, backendSslContext, timeout,
            concurrencySemaphore, activeRequests));
        addLoggingHandler(pipeline, "F");
    }

    /**
     * Add an alphabetical logging handler between pipeline stages.
     * Same pattern as RequestPipelineOrchestrator.addLoggingHandler and
     * NettyPacketToHttpConsumer.addLoggingHandlerLast.
     */
    private static void addLoggingHandler(ChannelPipeline pipeline, String name) {
        PIPELINE_LOGGING_OPTIONAL.ifPresent(
            logLevel -> pipeline.addLast(new LoggingHandler("s" + name, logLevel)));
    }

    private void shutdownEventLoopGroups() {
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    /** Graceful shutdown: stop accepting, wait for in-flight requests, then shut down. */
    public void stop() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (activeRequests.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (activeRequests.get() > 0) {
            log.warn("Shutting down with {} requests still in flight", activeRequests.get());
        }
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        try {
            requestTransformer.close();
        } catch (Exception e) {
            log.warn("Error closing request transformer", e);
        }
        try {
            responseTransformer.close();
        } catch (Exception e) {
            log.warn("Error closing response transformer", e);
        }
        log.info("TransformationShimProxy stopped");
    }

    /** Block until the server channel closes. */
    public void waitForClose() throws InterruptedException {
        serverChannel.closeFuture().sync();
    }

    /** Number of currently in-flight requests. */
    public int getActiveRequestCount() {
        return activeRequests.get();
    }
}
