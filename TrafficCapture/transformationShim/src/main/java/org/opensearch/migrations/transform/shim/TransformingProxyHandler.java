/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty handler that receives HTTP requests, transforms them asynchronously,
 * forwards to backend, transforms the response, and sends it back.
 * <p>
 * Production features: async I/O, configurable timeout, keep-alive, backpressure via semaphore.
 */
@Slf4j
public class TransformingProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
        "host", "content-length", "transfer-encoding", "connection",
        "keep-alive", "proxy-connection", "upgrade");
    private static final String URI_PATH_SEPARATOR = "/";

    private final URI backendUri;
    private final IJsonTransformer requestTransformer;
    private final IJsonTransformer responseTransformer;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Semaphore concurrencySemaphore;
    private final AtomicInteger activeRequests;

    TransformingProxyHandler(
        URI backendUri,
        IJsonTransformer requestTransformer,
        IJsonTransformer responseTransformer,
        HttpClient httpClient,
        Duration timeout,
        int maxConcurrentRequests,
        AtomicInteger activeRequests
    ) {
        this.backendUri = backendUri;
        this.requestTransformer = requestTransformer;
        this.responseTransformer = responseTransformer;
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.concurrencySemaphore = new Semaphore(maxConcurrentRequests);
        this.activeRequests = activeRequests;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);

        // Backpressure: reject if at capacity
        if (!concurrencySemaphore.tryAcquire()) {
            sendResponse(ctx, makeErrorResponse(HttpResponseStatus.SERVICE_UNAVAILABLE,
                "Proxy at capacity"), false);
            return;
        }

        activeRequests.incrementAndGet();
        // Retain request since we're going async
        request.retain();

        try {
            processRequest(ctx, request, keepAlive);
        } catch (RuntimeException e) {
            releaseRequestResources(request);
            log.error("Error processing request", e);
            sendResponse(ctx, makeErrorResponse(HttpResponseStatus.BAD_GATEWAY,
                "Proxy request processing failed"), false);
        }
    }

    private void processRequest(ChannelHandlerContext ctx, FullHttpRequest request, boolean keepAlive) {
        var requestMap = nettyRequestToMap(request);
        @SuppressWarnings("unchecked")
        var transformedRequest = (Map<String, Object>) requestTransformer.transformJson(requestMap);

        // Health check shortcut
        String uri = (String) transformedRequest.get(JsonKeysForHttpMessage.URI_KEY);
        if ("/_shim/health".equals(uri)) {
            releaseRequestResources(request);
            sendResponse(ctx, makeHealthResponse(), keepAlive);
            return;
        }

        var httpRequest = buildBackendRequest(transformedRequest);
        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
            .orTimeout(timeout.getSeconds(), java.util.concurrent.TimeUnit.SECONDS)
            .whenComplete((backendResponse, error) ->
                handleBackendResponse(ctx, request, keepAlive, backendResponse, error));
    }

    private void handleBackendResponse(
        ChannelHandlerContext ctx, FullHttpRequest request, boolean keepAlive,
        HttpResponse<String> backendResponse, Throwable error
    ) {
        try {
            if (error != null) {
                log.error("Backend call failed", error);
                sendResponse(ctx, makeErrorResponse(HttpResponseStatus.BAD_GATEWAY,
                    "Backend request failed"), false);
                return;
            }

            var responseMap = httpResponseToMap(backendResponse);
            @SuppressWarnings("unchecked")
            var transformedResponse = (Map<String, Object>) responseTransformer.transformJson(responseMap);
            sendResponse(ctx, mapToNettyResponse(transformedResponse), keepAlive);
        } catch (RuntimeException e) {
            log.error("Error processing response", e);
            sendResponse(ctx, makeErrorResponse(HttpResponseStatus.BAD_GATEWAY,
                "Response transformation failed"), false);
        } finally {
            releaseRequestResources(request);
        }
    }

    private void releaseRequestResources(FullHttpRequest request) {
        request.release();
        activeRequests.decrementAndGet();
        concurrencySemaphore.release();
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nettyRequestToMap(FullHttpRequest request) {
        var map = new LinkedHashMap<String, Object>();
        map.put(JsonKeysForHttpMessage.METHOD_KEY, request.method().name());
        map.put(JsonKeysForHttpMessage.URI_KEY, request.uri());
        map.put(JsonKeysForHttpMessage.PROTOCOL_KEY, request.protocolVersion().text());

        var headers = new LinkedHashMap<String, Object>();
        for (var name : request.headers().names()) {
            var values = request.headers().getAll(name);
            headers.put(name, values.size() == 1 ? values.get(0) : values);
        }
        map.put(JsonKeysForHttpMessage.HEADERS_KEY, headers);

        var body = request.content().toString(StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY, body);
            map.put(JsonKeysForHttpMessage.PAYLOAD_KEY, payload);
        }
        return map;
    }

    private HttpRequest buildBackendRequest(Map<String, Object> requestMap) {
        var method = (String) requestMap.get(JsonKeysForHttpMessage.METHOD_KEY);
        var uri = (String) requestMap.get(JsonKeysForHttpMessage.URI_KEY);
        var base = backendUri.toString().replaceAll("/+$", "");
        var path = uri.startsWith(URI_PATH_SEPARATOR) ? uri : URI_PATH_SEPARATOR + uri;

        var builder = HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .timeout(timeout);

        copyRequestHeaders(requestMap, builder);

        var body = extractBodyString(requestMap);
        var bodyPublisher = body != null
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.noBody();
        builder.method(method, bodyPublisher);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private void copyRequestHeaders(Map<String, Object> requestMap, HttpRequest.Builder builder) {
        var headers = (Map<String, Object>) requestMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
        if (headers == null) return;
        for (var entry : headers.entrySet()) {
            if (RESTRICTED_HEADERS.contains(entry.getKey().toLowerCase())) continue;
            addHeaderValues(entry.getKey(), entry.getValue(), builder::header);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractBodyString(Map<String, Object> requestMap) {
        var payload = (Map<String, Object>) requestMap.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        return payload != null
            ? (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY)
            : null;
    }

    static Map<String, Object> httpResponseToMap(HttpResponse<String> response) {
        var map = new LinkedHashMap<String, Object>();
        map.put(JsonKeysForHttpMessage.STATUS_CODE_KEY, response.statusCode());
        map.put(JsonKeysForHttpMessage.PROTOCOL_KEY, "HTTP/1.1");

        var headers = new LinkedHashMap<String, Object>();
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":")) {
                headers.put(name, values.size() == 1 ? values.get(0) : values);
            }
        });
        map.put(JsonKeysForHttpMessage.HEADERS_KEY, headers);

        var body = response.body();
        if (body != null && !body.isEmpty()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY, body);
            map.put(JsonKeysForHttpMessage.PAYLOAD_KEY, payload);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    static FullHttpResponse mapToNettyResponse(Map<String, Object> responseMap) {
        var statusCode = responseMap.get(JsonKeysForHttpMessage.STATUS_CODE_KEY);
        int code = statusCode instanceof Number ? ((Number) statusCode).intValue() : 200;

        byte[] bodyBytes = extractResponseBodyBytes(responseMap);

        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(code),
            Unpooled.wrappedBuffer(bodyBytes)
        );

        copyResponseHeaders(responseMap, response);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static byte[] extractResponseBodyBytes(Map<String, Object> responseMap) {
        var payload = (Map<String, Object>) responseMap.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        if (payload != null) {
            var textBody = (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
            if (textBody != null) {
                return textBody.getBytes(StandardCharsets.UTF_8);
            }
        }
        return new byte[0];
    }

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "transfer-encoding");

    @SuppressWarnings("unchecked")
    private static void copyResponseHeaders(Map<String, Object> responseMap, FullHttpResponse response) {
        var headers = (Map<String, Object>) responseMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
        if (headers == null) return;
        for (var entry : headers.entrySet()) {
            if (SKIP_RESPONSE_HEADERS.contains(entry.getKey().toLowerCase())) continue;
            addHeaderValues(entry.getKey(), entry.getValue(),
                (name, val) -> response.headers().add(name, val));
        }
    }

    private static void addHeaderValues(String name, Object value, java.util.function.BiConsumer<String, String> adder) {
        if (value instanceof List) {
            for (var v : (List<?>) value) {
                adder.accept(name, v.toString());
            }
        } else {
            adder.accept(name, value.toString());
        }
    }

    private static FullHttpResponse makeErrorResponse(HttpResponseStatus status, String message) {
        var body = message.getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        return response;
    }

    private static FullHttpResponse makeHealthResponse() {
        var body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return response;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unhandled exception in proxy handler", cause);
        ctx.close();
    }
}
