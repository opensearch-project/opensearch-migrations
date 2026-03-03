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
import org.opensearch.migrations.transform.shim.ShimMain;
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
 * Verifies that transforms using {@code URLSearchParams} work in GraalVM with the polyfill.
 *
 * <p><b>Bug:</b> GraalVM's JavaScript engine does not provide the {@code URLSearchParams} Web API.
 * Transform scripts that use it throw {@code ReferenceError: URLSearchParams is not defined}.
 *
 * <p><b>Fix:</b> Prepend a minimal polyfill implementing {@code get()}, {@code has()}, and
 * {@code getAll()} before the transform script.
 */
class URLSearchParamsPolyfillTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    /** Minimal URLSearchParams polyfill — uses the production polyfill from ShimMain. */

    /** Transform that parses query params with URLSearchParams and exercises all polyfill methods. */
    private static final String TRANSFORM = ShimMain.JS_POLYFILL +
        "(function(bindings) {\n" +
        "  return function(request) {\n" +
        "    var uri = request.get('URI');\n" +
        "    var qIdx = uri.indexOf('?');\n" +
        "    if (qIdx >= 0) {\n" +
        "      var params = new URLSearchParams(uri.substring(qIdx + 1));\n" +
        "      request.set('URI', '/parsed');\n" +
        "      request.set('method', 'POST');\n" +
        "      var payload = new Map();\n" +
        "      var body = new Map();\n" +
        "      // get + has\n" +
        "      body.set('q', params.get('q'));\n" +
        "      body.set('hasQ', params.has('q'));\n" +
        "      body.set('missingKey', params.get('nope'));\n" +
        "      body.set('hasMissing', params.has('nope'));\n" +
        "      // getAll\n" +
        "      body.set('allTags', JSON.stringify(params.getAll('tag')));\n" +
        "      // forEach\n" +
        "      var forEachPairs = [];\n" +
        "      params.forEach(function(v, k) { forEachPairs.push(k + '=' + v); });\n" +
        "      body.set('forEachResult', forEachPairs.join(','));\n" +
        "      // keys, values, entries\n" +
        "      body.set('keys', JSON.stringify(params.keys()));\n" +
        "      body.set('values', JSON.stringify(params.values()));\n" +
        "      body.set('entries', JSON.stringify(params.entries()));\n" +
        "      // toString\n" +
        "      body.set('toString', params.toString());\n" +
        "      // delete\n" +
        "      body.set('hasRowsBefore', params.has('rows'));\n" +
        "      params.delete('rows');\n" +
        "      body.set('hasRowsAfter', params.has('rows'));\n" +
        "      payload.set('inlinedJsonBody', body);\n" +
        "      request.set('payload', payload);\n" +
        "    }\n" +
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
    void urlSearchParams_allPolyfillMethods() throws Exception {
        int backendPort = findFreePort();
        int proxyPort = findFreePort();

        startEchoBackend(backendPort);

        var transform = new JavascriptTransformer(TRANSFORM, new LinkedHashMap<>());
        var targets = Map.of("backend",
            new Target("backend", URI.create("http://localhost:" + backendPort), transform, null, null));
        proxy = new ShimProxy(proxyPort, targets, "backend", List.of());
        proxy.start();

        // Multi-value 'tag' param exercises getAll; 'rows' exercises delete
        var resp = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxyPort + "/search?q=hello&rows=10&tag=a&tag=b"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        var body = resp.body();

        // get + has
        assertTrue(body.contains("\"q\":\"hello\""), "get('q') should return 'hello': " + body);
        assertTrue(body.contains("\"hasQ\":true"), "has('q') should be true: " + body);
        assertTrue(body.contains("\"missingKey\":null"), "get('nope') should return null: " + body);
        assertTrue(body.contains("\"hasMissing\":false"), "has('nope') should be false: " + body);

        // getAll
        assertTrue(body.contains("\"allTags\":\"[\\\"a\\\",\\\"b\\\"]\""), "getAll('tag') should return [a,b]: " + body);

        // forEach
        assertTrue(body.contains("\"forEachResult\":\""), "forEach should produce pairs: " + body);
        assertTrue(body.contains("q=hello"), "forEach should include q=hello: " + body);

        // keys
        assertTrue(body.contains("\"keys\":\""), "keys() should return array: " + body);

        // values
        assertTrue(body.contains("\"values\":\""), "values() should return array: " + body);

        // entries
        assertTrue(body.contains("\"entries\":\""), "entries() should return array: " + body);

        // toString
        assertTrue(body.contains("\"toString\":\""), "toString() should return query string: " + body);

        // delete
        assertTrue(body.contains("\"hasRowsBefore\":true"), "has('rows') should be true before delete: " + body);
        assertTrue(body.contains("\"hasRowsAfter\":false"), "has('rows') should be false after delete: " + body);
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
