/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic proxy tests for the TransformationShimProxy.
 * Uses a simple Netty backend to verify pass-through and transform behavior.
 */
class TransformationShimProxyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    /** Identity transform — returns input unchanged. */
    private static final IJsonTransformer IDENTITY = input -> input;

    private NioEventLoopGroup backendGroup;
    private Channel backendChannel;
    private int backendPort;
    private TransformationShimProxy proxy;
    private int proxyPort;

    @BeforeEach
    void setUp() throws Exception {
        backendPort = findFreePort();
        proxyPort = findFreePort();
        startMockBackend();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) proxy.stop();
        if (backendChannel != null) backendChannel.close().sync();
        if (backendGroup != null) backendGroup.shutdownGracefully().sync();
    }

    @Test
    void passThrough_returnsBackendResponse() throws Exception {
        proxy = new TransformationShimProxy(proxyPort, URI.create("http://localhost:" + backendPort),
            IDENTITY, IDENTITY);
        proxy.start();

        var response = httpGet("http://localhost:" + proxyPort + "/test?q=hello");
        var json = MAPPER.readValue(response, Map.class);

        assertEquals("/test?q=hello", json.get("echoUri"));
        assertEquals("GET", json.get("echoMethod"));
    }

    @Test
    void healthEndpoint_returnsOk() throws Exception {
        proxy = new TransformationShimProxy(proxyPort, URI.create("http://localhost:" + backendPort),
            IDENTITY, IDENTITY);
        proxy.start();

        var response = httpGet("http://localhost:" + proxyPort + "/_shim/health");
        assertTrue(response.contains("\"status\":\"ok\""));
    }

    @SuppressWarnings("unchecked")
    @Test
    void requestTransform_modifiesUri() throws Exception {
        IJsonTransformer uriRewriter = input -> {
            var map = (Map<String, Object>) input;
            var uri = (String) map.get(JsonKeysForHttpMessage.URI_KEY);
            map.put(JsonKeysForHttpMessage.URI_KEY, uri.replace("/old-path", "/new-path"));
            return map;
        };

        proxy = new TransformationShimProxy(proxyPort, URI.create("http://localhost:" + backendPort),
            uriRewriter, IDENTITY);
        proxy.start();

        var response = httpGet("http://localhost:" + proxyPort + "/old-path?q=test");
        var json = MAPPER.readValue(response, Map.class);

        assertEquals("/new-path?q=test", json.get("echoUri"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void responseTransform_modifiesBody() throws Exception {
        IJsonTransformer responseTransform = input -> {
            var map = (Map<String, Object>) input;
            var payload = (Map<String, Object>) map.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
            if (payload != null) {
                var body = (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
                if (body != null) {
                    payload.put(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY,
                        body.replace("\"echoUri\"", "\"transformedUri\""));
                }
            }
            return map;
        };

        proxy = new TransformationShimProxy(proxyPort, URI.create("http://localhost:" + backendPort),
            IDENTITY, responseTransform);
        proxy.start();

        var response = httpGet("http://localhost:" + proxyPort + "/test");
        assertTrue(response.contains("transformedUri"), "Response should contain transformed key");
    }

    // --- Mock backend ---

    private void startMockBackend() throws InterruptedException {
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
                        .addLast(new EchoHandler());
                }
            })
            .bind(backendPort).sync().channel();
    }

    /** Echoes back the request URI and method as JSON. */
    static class EchoHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            var json = String.format("{\"echoUri\":\"%s\",\"echoMethod\":\"%s\"}",
                request.uri(), request.method().name());
            var body = json.getBytes(StandardCharsets.UTF_8);
            var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response);
        }
    }

    // --- Helpers ---

    private static String httpGet(String url) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
