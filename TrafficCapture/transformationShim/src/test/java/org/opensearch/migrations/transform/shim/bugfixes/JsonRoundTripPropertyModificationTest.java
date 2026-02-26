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
 * Verifies that JavaScript transforms can add new properties to the input object and have
 * those changes reflected in the output sent to the backend.
 *
 * <p><b>Bug:</b> GraalVM polyglot interop creates shadow properties when JavaScript code assigns
 * new properties on a Java {@code Map} proxy (e.g. {@code request.newKey = 'value'}). The
 * assignment succeeds in JS but does not call {@code Map.put()} on the underlying Java Map,
 * so the new property is invisible to Java code.
 *
 * <p><b>Fix:</b> {@code JavascriptTransformer.runScript()} wraps the call with a JSON round-trip:
 * serialize the input Map to a JSON string, {@code JSON.parse} it in JS to create a native JS
 * object, call the transform, {@code JSON.stringify} the result, and parse back in Java. This
 * ensures all property modifications are captured.
 */
class JsonRoundTripPropertyModificationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Transform that adds a new property and modifies the body.
     * Without the JSON round-trip fix, the new 'payload' property would be invisible to Java.
     */
    private static final String TRANSFORM =
        "(function(bindings) {\n" +
        "  return function(request) {\n" +
        "    request.URI = '/modified';\n" +
        "    request.method = 'POST';\n" +
        "    request.payload = { inlinedTextBody: JSON.stringify({ added: true, method: request.method }) };\n" +
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
    void jsPropertyModifications_reflectedInBackendRequest() throws Exception {
        int backendPort = findFreePort();
        int proxyPort = findFreePort();

        startEchoBackend(backendPort);

        var transform = new JavascriptTransformer(TRANSFORM, new LinkedHashMap<>());
        var targets = Map.of("backend",
            new Target("backend", URI.create("http://localhost:" + backendPort), transform, null, null));
        proxy = new ShimProxy(proxyPort, targets, "backend", List.of());
        proxy.start();

        var resp = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxyPort + "/original"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        // The echo backend returns the body it received — should contain the JS-added properties
        assertTrue(resp.body().contains("\"added\":true"),
            "Expected JS-added property 'added: true' in response, got: " + resp.body());
        assertTrue(resp.body().contains("\"method\":\"POST\""),
            "Expected method to be POST after JS modification, got: " + resp.body());
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
                        .addLast(new EchoBodyHandler());
                }
            })
            .bind(port).sync().channel();
    }

    /** Returns the received request body as the response. */
    static class EchoBodyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            byte[] body = new byte[request.content().readableBytes()];
            request.content().readBytes(body);
            if (body.length == 0) body = "{}".getBytes(StandardCharsets.UTF_8);
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
