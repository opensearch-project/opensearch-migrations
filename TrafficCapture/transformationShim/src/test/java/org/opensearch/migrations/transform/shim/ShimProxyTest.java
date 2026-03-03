package org.opensearch.migrations.transform.shim;

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
import java.util.Set;

import org.opensearch.migrations.transform.shim.validation.DocCountValidator;
import org.opensearch.migrations.transform.shim.validation.FieldIgnoringEquality;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the multi-target ShimProxy.
 * Uses two mock backends to verify parallel dispatch, validation headers, and primary selection.
 */
class ShimProxyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    private NioEventLoopGroup backendGroupA;
    private NioEventLoopGroup backendGroupB;
    private Channel backendChannelA;
    private Channel backendChannelB;
    private int backendPortA;
    private int backendPortB;
    private ShimProxy proxy;
    private int proxyPort;

    @BeforeEach
    void setUp() throws Exception {
        backendPortA = findFreePort();
        backendPortB = findFreePort();
        proxyPort = findFreePort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) proxy.stop();
        if (backendChannelA != null) backendChannelA.close().sync();
        if (backendChannelB != null) backendChannelB.close().sync();
        if (backendGroupA != null) backendGroupA.shutdownGracefully().sync();
        if (backendGroupB != null) backendGroupB.shutdownGracefully().sync();
    }

    @Test
    void singleTarget_passthrough() throws Exception {
        startBackend("A", backendPortA, "{\"response\":{\"numFound\":10}}");
        var targets = Map.of("alpha", new Target("alpha", URI.create("http://localhost:" + backendPortA)));
        proxy = new ShimProxy(proxyPort, targets, "alpha", List.of());
        proxy.start();

        var resp = httpGet("http://localhost:" + proxyPort + "/test");
        var json = MAPPER.readValue(resp.body(), Map.class);
        assertEquals(10, ((Map) json.get("response")).get("numFound"));
        assertEquals("alpha", resp.headers().firstValue("X-Shim-Primary").orElse(null));
    }

    @Test
    void dualTarget_validationPass() throws Exception {
        String body = "{\"response\":{\"numFound\":10}}";
        startBackend("A", backendPortA, body);
        startBackend("B", backendPortB, body);

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPortA)));
        targets.put("beta", new Target("beta", URI.create("http://localhost:" + backendPortB)));

        var validators = List.of(
            new ValidationRule("field-equality", List.of("alpha", "beta"),
                new FieldIgnoringEquality("alpha", "beta", Set.of())));

        proxy = new ShimProxy(proxyPort, targets, "alpha", null, validators,
            null, false, Duration.ofSeconds(5));
        proxy.start();

        var resp = httpGet("http://localhost:" + proxyPort + "/test");
        assertEquals(200, resp.statusCode());
        assertEquals("PASS", resp.headers().firstValue("X-Validation-Status").orElse(null));
        assertNotNull(resp.headers().firstValue("X-Target-alpha-StatusCode").orElse(null));
        assertNotNull(resp.headers().firstValue("X-Target-beta-StatusCode").orElse(null));
    }

    @Test
    void dualTarget_validationFail() throws Exception {
        startBackend("A", backendPortA, "{\"response\":{\"numFound\":10}}");
        startBackend("B", backendPortB, "{\"response\":{\"numFound\":5}}");

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPortA)));
        targets.put("beta", new Target("beta", URI.create("http://localhost:" + backendPortB)));

        var validators = List.of(
            new ValidationRule("doc-count", List.of("alpha", "beta"),
                new DocCountValidator("alpha", "beta", DocCountValidator.Comparison.EQUAL)));

        proxy = new ShimProxy(proxyPort, targets, "alpha", null, validators,
            null, false, Duration.ofSeconds(5));
        proxy.start();

        var resp = httpGet("http://localhost:" + proxyPort + "/test");
        assertEquals(200, resp.statusCode()); // primary still returns OK
        assertEquals("FAIL", resp.headers().firstValue("X-Validation-Status").orElse(null));
    }

    @Test
    void primarySelection_returnsPrimaryBody() throws Exception {
        startBackend("A", backendPortA, "{\"source\":\"alpha\"}");
        startBackend("B", backendPortB, "{\"source\":\"beta\"}");

        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("alpha", new Target("alpha", URI.create("http://localhost:" + backendPortA)));
        targets.put("beta", new Target("beta", URI.create("http://localhost:" + backendPortB)));

        // beta is primary
        proxy = new ShimProxy(proxyPort, targets, "beta", null, List.of(),
            null, false, Duration.ofSeconds(5));
        proxy.start();

        var resp = httpGet("http://localhost:" + proxyPort + "/test");
        assertTrue(resp.body().contains("\"source\":\"beta\""));
        assertEquals("beta", resp.headers().firstValue("X-Shim-Primary").orElse(null));
    }

    // --- Mock backends ---

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
                        .addLast(new StaticResponseHandler(responseBody));
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

    /** Returns a fixed JSON response for every request. */
    static class StaticResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final String responseBody;

        StaticResponseHandler(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response);
        }
    }

    // --- Helpers ---

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
