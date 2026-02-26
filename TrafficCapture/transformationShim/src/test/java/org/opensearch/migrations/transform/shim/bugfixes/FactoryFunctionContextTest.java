package org.opensearch.migrations.transform.shim.bugfixes;

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

import org.opensearch.migrations.transform.JavascriptTransformer;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that IIFE factory function transforms work correctly through the shim proxy.
 *
 * <p><b>Bug:</b> {@code JavascriptTransformer} was created with {@code null} context. For factory
 * function scripts like {@code (function(bindings) { return function(msg) { ... }; })}, null context
 * means the outer function is stored as the transform instead of being invoked to produce the inner
 * function. When the transform is later called with a request map, the outer function treats it as
 * {@code bindings} and returns the inner function — which is not a Map.
 *
 * <p><b>Fix:</b> Pass a non-null context ({@code new LinkedHashMap<>()}) so the outer function is
 * called during construction, producing the inner transform function.
 */
class FactoryFunctionContextTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    /** Factory function that rewrites the URI — only works if the outer function is invoked with context. */
    private static final String FACTORY_TRANSFORM =
        "(function(bindings) {\n" +
        "  return function(request) {\n" +
        "    request.URI = '/factory-applied';\n" +
        "    return request;\n" +
        "  };\n" +
        "})";

    private NioEventLoopGroup backendGroup;
    private Channel backendChannel;
    private ShimProxy proxy;

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) proxy.stop();
        if (backendChannel != null) backendChannel.close().sync();
        if (backendGroup != null) backendGroup.shutdownGracefully().sync();
    }

    @Test
    void factoryFunctionTransform_appliesInnerFunction() throws Exception {
        int backendPort = findFreePort();
        int proxyPort = findFreePort();

        startEchoBackend(backendPort);

        var transform = new JavascriptTransformer(FACTORY_TRANSFORM, new LinkedHashMap<>());
        var targets = Map.of("backend",
            new Target("backend", URI.create("http://localhost:" + backendPort), transform, null, null));
        proxy = new ShimProxy(proxyPort, targets, "backend", List.of());
        proxy.start();

        var resp = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + proxyPort + "/original")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        // The echo backend returns the URI it received — should be the transformed one
        assertTrue(resp.body().contains("/factory-applied"),
            "Expected transform to rewrite URI to /factory-applied, got: " + resp.body());
    }

    private void startEchoBackend(int port) throws InterruptedException {
        backendGroup = new NioEventLoopGroup(1);
        backendChannel = new ServerBootstrap()
            .group(backendGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new HttpServerCodec())
                        .addLast(new HttpObjectAggregator(1024 * 1024))
                        .addLast(new EchoUriHandler());
                }
            })
            .bind(port).sync().channel();
    }

    /** Returns the received request URI as JSON. */
    static class EchoUriHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            byte[] body = ("{\"receivedUri\":\"" + request.uri() + "\"}").getBytes(StandardCharsets.UTF_8);
            var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response);
        }
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
