package org.opensearch.migrations.transform.shim.tracing;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.shim.ShimProxy;
import org.opensearch.migrations.transform.shim.validation.Target;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
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
import io.netty.handler.codec.http.HttpVersion;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test verifying OTel spans are emitted through the full ShimProxy request path.
 */
class ShimProxyInstrumentationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    private InMemoryInstrumentationBundle bundle;
    private RootShimProxyContext rootContext;
    private NioEventLoopGroup backendGroupA;
    private NioEventLoopGroup backendGroupB;
    private Channel backendChannelA;
    private Channel backendChannelB;
    private ShimProxy proxy;
    private int proxyPort;

    @BeforeEach
    void setUp() throws Exception {
        bundle = new InMemoryInstrumentationBundle(true, true);
        rootContext = new RootShimProxyContext(bundle.openTelemetrySdk, IContextTracker.DO_NOTHING_TRACKER);
        proxyPort = findFreePort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) proxy.stop();
        if (backendChannelA != null) backendChannelA.close().sync();
        if (backendChannelB != null) backendChannelB.close().sync();
        if (backendGroupA != null) backendGroupA.shutdownGracefully().sync();
        if (backendGroupB != null) backendGroupB.shutdownGracefully().sync();
        bundle.close();
    }

    @Test
    void requestThroughProxy_emitsShimRequestSpan() throws Exception {
        int backendPort = findFreePort();
        startBackend("A", backendPort, "{\"status\":\"ok\"}");

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPort)));

        proxy = new ShimProxy(proxyPort, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), ShimProxy.DEFAULT_MAX_CONTENT_LENGTH, rootContext, null);
        proxy.start();

        var resp = httpGet("http://localhost:" + proxyPort + "/test/endpoint");
        assertEquals(200, resp.statusCode());
        Thread.sleep(200);

        var spans = bundle.getFinishedSpans();
        assertFalse(spans.isEmpty(), "Expected at least one span");

        SpanData requestSpan = spans.stream()
            .filter(s -> "shimRequest".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No shimRequest span found"));

        assertEquals("GET", requestSpan.getAttributes().get(ShimRequestContext.HTTP_METHOD_ATTR));
        assertEquals("/test/endpoint", requestSpan.getAttributes().get(ShimRequestContext.HTTP_URL_ATTR));
    }

    @Test
    void requestThroughProxy_emitsCountMetric() throws Exception {
        int backendPort = findFreePort();
        startBackend("A", backendPort, "{\"status\":\"ok\"}");

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPort)));

        proxy = new ShimProxy(proxyPort, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), ShimProxy.DEFAULT_MAX_CONTENT_LENGTH, rootContext, null);
        proxy.start();

        httpGet("http://localhost:" + proxyPort + "/metrics-check");
        Thread.sleep(200);

        var metrics = bundle.getFinishedMetrics();
        long count = InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "shimRequestCount");
        assertTrue(count > 0, "shimRequestCount should be > 0 after a request");
    }

    @Test
    void proxyWithoutRootContext_emitsNoSpans() throws Exception {
        int backendPort = findFreePort();
        startBackend("A", backendPort, "{\"status\":\"ok\"}");

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPort)));

        proxy = new ShimProxy(proxyPort, targets, "alpha", List.of());
        proxy.start();

        httpGet("http://localhost:" + proxyPort + "/no-otel");
        Thread.sleep(200);

        assertTrue(bundle.getFinishedSpans().isEmpty(), "No spans should be emitted without rootContext");
    }

    @Test
    void dualTargetDispatch_emitsTargetDispatchSpans() throws Exception {
        int portA = findFreePort();
        int portB = findFreePort();
        startBackend("A", portA, "{\"source\":\"alpha\"}");
        startBackend("B", portB, "{\"source\":\"beta\"}");

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + portA)));
        targets.put("beta", new Target("beta", URI.create("http://localhost:" + portB)));

        proxy = new ShimProxy(proxyPort, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), ShimProxy.DEFAULT_MAX_CONTENT_LENGTH, rootContext);
        proxy.start();

        httpGet("http://localhost:" + proxyPort + "/dual");
        Thread.sleep(300);

        var dispatchSpans = bundle.getFinishedSpans().stream()
            .filter(s -> "targetDispatch".equals(s.getName()))
            .toList();
        assertEquals(2, dispatchSpans.size(), "Expected 2 targetDispatch spans");

        assertTrue(dispatchSpans.stream().anyMatch(
            s -> "alpha".equals(s.getAttributes().get(TargetDispatchContext.TARGET_NAME_ATTR))));
        assertTrue(dispatchSpans.stream().anyMatch(
            s -> "beta".equals(s.getAttributes().get(TargetDispatchContext.TARGET_NAME_ATTR))));

        // Verify status codes are recorded
        for (var span : dispatchSpans) {
            assertEquals(200L, span.getAttributes().get(TargetDispatchContext.HTTP_STATUS_CODE_ATTR));
        }
    }

    @Test
    void targetWithTransform_emitsTransformSpan() throws Exception {
        int backendPort = findFreePort();
        startBackend("A", backendPort, "{\"status\":\"ok\"}");

        // Identity transform — returns input unchanged
        IJsonTransformer identityTransform = input -> input;

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPort),
            identityTransform, null, null));

        proxy = new ShimProxy(proxyPort, targets, "alpha", null, List.of(),
            null, false, Duration.ofSeconds(5), ShimProxy.DEFAULT_MAX_CONTENT_LENGTH, rootContext);
        proxy.start();

        httpGet("http://localhost:" + proxyPort + "/with-transform");
        Thread.sleep(300);

        var transformSpans = bundle.getFinishedSpans().stream()
            .filter(s -> "transform".equals(s.getName()))
            .toList();
        assertFalse(transformSpans.isEmpty(), "Expected at least one transform span");

        SpanData transformSpan = transformSpans.get(0);
        assertEquals("request", transformSpan.getAttributes().get(TransformContext.TRANSFORM_DIRECTION_ATTR));
        assertEquals("alpha", transformSpan.getAttributes().get(TransformContext.TARGET_NAME_ATTR));
    }

    @Test
    void dispatchToUnreachableTarget_recordsException() throws Exception {
        // Point at a port with nothing listening
        int deadPort = findFreePort();

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("dead", new Target("dead", URI.create("http://localhost:" + deadPort)));

        proxy = new ShimProxy(proxyPort, targets, "dead", null, List.of(),
            null, false, Duration.ofSeconds(2), ShimProxy.DEFAULT_MAX_CONTENT_LENGTH, rootContext);
        proxy.start();

        httpGet("http://localhost:" + proxyPort + "/fail");
        Thread.sleep(300);

        // Should still have a shimRequest span
        var requestSpans = bundle.getFinishedSpans().stream()
            .filter(s -> "shimRequest".equals(s.getName()))
            .toList();
        assertFalse(requestSpans.isEmpty(), "Expected shimRequest span even on failure");
    }

    // --- Mock backend ---

    private void startBackend(String label, int port, String responseBody) throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new HttpServerCodec())
                        .addLast(new HttpObjectAggregator(1024 * 1024))
                        .addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                                var resp = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                    Unpooled.wrappedBuffer(body));
                                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                                resp.headers().set(HttpHeaderNames.CONNECTION, "close");
                                ctx.writeAndFlush(resp);
                            }
                        });
                }
            })
            .bind(port).sync().channel();

        if ("A".equals(label)) {
            backendGroupA = group;
            backendChannelA = channel;
        } else {
            backendGroupB = group;
            backendChannelB = channel;
        }
    }

    private static HttpResponse<String> httpGet(String url) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
