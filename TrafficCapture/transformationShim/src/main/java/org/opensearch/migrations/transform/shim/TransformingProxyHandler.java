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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty handler that receives an HTTP request, transforms it, forwards to backend,
 * transforms the response, and sends it back.
 */
@Slf4j
public class TransformingProxyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final URI backendUri;
    private final IJsonTransformer requestTransformer;
    private final IJsonTransformer responseTransformer;
    private final HttpClient httpClient;

    public TransformingProxyHandler(URI backendUri,
                                    IJsonTransformer requestTransformer,
                                    IJsonTransformer responseTransformer) {
        this.backendUri = backendUri;
        this.requestTransformer = requestTransformer;
        this.responseTransformer = responseTransformer;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        ctx.channel().eventLoop().execute(() -> {
            try {
                var response = processRequest(request);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                log.error("Error processing request", e);
                sendError(ctx, e);
            }
        });
    }

    private FullHttpResponse processRequest(FullHttpRequest nettyRequest) throws Exception {
        // 1. Convert incoming request to map format
        var requestMap = nettyRequestToMap(nettyRequest);
        log.debug("Original request map: {}", requestMap);

        // 2. Transform request
        @SuppressWarnings("unchecked")
        var transformedRequest = (Map<String, Object>) requestTransformer.transformJson(requestMap);
        log.debug("Transformed request map: {}", transformedRequest);

        // 3. Forward to backend
        var backendResponse = forwardToBackend(transformedRequest);

        // 4. Convert backend response to map format
        var responseMap = httpResponseToMap(backendResponse);
        log.debug("Backend response map: {}", responseMap);

        // 5. Transform response
        @SuppressWarnings("unchecked")
        var transformedResponse = (Map<String, Object>) responseTransformer.transformJson(responseMap);
        log.debug("Transformed response map: {}", transformedResponse);

        // 6. Convert back to Netty response
        return mapToNettyResponse(transformedResponse);
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

    private HttpResponse<String> forwardToBackend(Map<String, Object> requestMap) throws Exception {
        var method = (String) requestMap.get(JsonKeysForHttpMessage.METHOD_KEY);
        var uri = (String) requestMap.get(JsonKeysForHttpMessage.URI_KEY);
        var targetUri = URI.create(backendUri.toString() + uri);

        String body = null;
        @SuppressWarnings("unchecked")
        var payload = (Map<String, Object>) requestMap.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        if (payload != null) {
            body = (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
        }

        var builder = HttpRequest.newBuilder().uri(targetUri);

        // Copy transformed headers (skip hop-by-hop and restricted headers)
        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) requestMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
        var restrictedHeaders = java.util.Set.of(
            "host", "content-length", "transfer-encoding", "connection",
            "keep-alive", "proxy-connection", "upgrade");
        if (headers != null) {
            for (var entry : headers.entrySet()) {
                var key = entry.getKey().toLowerCase();
                if (restrictedHeaders.contains(key)) continue;
                var val = entry.getValue();
                if (val instanceof List) {
                    for (var v : (List<?>) val) {
                        builder.header(entry.getKey(), v.toString());
                    }
                } else {
                    builder.header(entry.getKey(), val.toString());
                }
            }
        }

        var bodyPublisher = body != null
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.noBody();
        builder.method(method, bodyPublisher);

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, Object> httpResponseToMap(HttpResponse<String> response) {
        var map = new LinkedHashMap<String, Object>();
        map.put(JsonKeysForHttpMessage.STATUS_CODE_KEY, response.statusCode());
        map.put(JsonKeysForHttpMessage.PROTOCOL_KEY, "HTTP/1.1");

        var headers = new LinkedHashMap<String, Object>();
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":")) { // skip HTTP/2 pseudo-headers
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

        byte[] bodyBytes = new byte[0];
        var payload = (Map<String, Object>) responseMap.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        if (payload != null) {
            var textBody = (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
            if (textBody != null) {
                bodyBytes = textBody.getBytes(StandardCharsets.UTF_8);
            }
        }

        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(code),
            Unpooled.wrappedBuffer(bodyBytes)
        );

        var headers = (Map<String, Object>) responseMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
        if (headers != null) {
            for (var entry : headers.entrySet()) {
                var key = entry.getKey().toLowerCase();
                if (key.equals("content-length") || key.equals("transfer-encoding")) continue;
                var val = entry.getValue();
                if (val instanceof List) {
                    for (var v : (List<?>) val) {
                        response.headers().add(entry.getKey(), v.toString());
                    }
                } else {
                    response.headers().set(entry.getKey(), val.toString());
                }
            }
        }
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
        return response;
    }

    private void sendError(ChannelHandlerContext ctx, Exception e) {
        var body = ("Proxy error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.BAD_GATEWAY,
            Unpooled.wrappedBuffer(body)
        );
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unhandled exception in proxy handler", cause);
        ctx.close();
    }
}
