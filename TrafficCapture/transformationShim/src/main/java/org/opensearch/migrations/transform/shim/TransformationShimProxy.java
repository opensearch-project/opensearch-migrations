/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.ThreadSafeTransformerWrapper;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A production-ready HTTP proxy that transforms requests and responses using {@link IJsonTransformer}.
 * <p>
 * Supports: TLS (frontside via {@code Supplier<SSLEngine>}, backside via {@code SSLContext}),
 * async backend calls, configurable timeouts, HTTP keep-alive, backpressure, graceful shutdown,
 * and thread-safe transformers.
 */
@Slf4j
public class TransformationShimProxy {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(150);
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 100;
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    @Getter
    private final int port;
    private final URI backendUri;
    private final IJsonTransformer requestTransformer;
    private final IJsonTransformer responseTransformer;
    private final Supplier<SSLEngine> sslEngineSupplier;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final int maxConcurrentRequests;

    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /**
     * Full constructor with all production options.
     */
    public TransformationShimProxy(
        int port,
        URI backendUri,
        Supplier<IJsonTransformer> requestTransformerSupplier,
        Supplier<IJsonTransformer> responseTransformerSupplier,
        Supplier<SSLEngine> sslEngineSupplier,
        SSLContext backendSslContext,
        boolean allowInsecureBackend,
        Duration timeout,
        int maxConcurrentRequests
    ) {
        this.port = port;
        this.backendUri = backendUri;
        this.requestTransformer = new ThreadSafeTransformerWrapper(requestTransformerSupplier);
        this.responseTransformer = new ThreadSafeTransformerWrapper(responseTransformerSupplier);
        this.sslEngineSupplier = sslEngineSupplier;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.maxConcurrentRequests = maxConcurrentRequests > 0 ? maxConcurrentRequests : DEFAULT_MAX_CONCURRENT_REQUESTS;
        this.httpClient = buildHttpClient(backendSslContext, allowInsecureBackend, this.timeout);
    }

    /** Convenience constructor for testing — no TLS, default timeout, default concurrency. */
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
        this.httpClient = buildHttpClient(null, false, this.timeout);
    }

    private static HttpClient buildHttpClient(SSLContext backendSslContext, boolean allowInsecure, Duration timeout) {
        var builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .version(HttpClient.Version.HTTP_1_1);
        var sslCtx = resolveBackendSslContext(backendSslContext, allowInsecure);
        if (sslCtx != null) {
            builder.sslContext(sslCtx);
        }
        return builder.build();
    }

    @SuppressWarnings("java:S4830") // Intentionally trust-all for --insecureDestination mode
    private static SSLContext resolveBackendSslContext(SSLContext provided, boolean allowInsecure) {
        if (provided != null) return provided;
        if (!allowInsecure) return null;
        try {
            var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String t) {
                    // Intentionally empty — insecure mode trusts all certificates
                }
                public void checkServerTrusted(X509Certificate[] certs, String t) {
                    // Intentionally empty — insecure mode trusts all certificates
                }
            }}, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to create insecure SSLContext", e);
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
                    if (sslEngineSupplier != null) {
                        ch.pipeline().addLast(new SslHandler(sslEngineSupplier.get()));
                    }
                    ch.pipeline().addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                        new TransformingProxyHandler(
                            backendUri,
                            requestTransformer,
                            responseTransformer,
                            httpClient,
                            timeout,
                            maxConcurrentRequests,
                            activeRequests
                        )
                    );
                }
            })
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("TransformationShimProxy started on port {}, backend={}, timeout={}s, maxConcurrent={}, frontTLS={}, backTLS={}",
                port, backendUri, timeout.getSeconds(), maxConcurrentRequests,
                sslEngineSupplier != null, "https".equalsIgnoreCase(backendUri.getScheme()));
        } catch (Exception e) {
            shutdownEventLoopGroups();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
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
