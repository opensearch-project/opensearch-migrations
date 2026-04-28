package org.opensearch.migrations.transform.shim.netty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Utility methods for converting between Netty HTTP objects and the Map-based
 * representation used by IJsonTransformer. Shared across pipeline handlers.
 */
public final class HttpMessageUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE_REF = new TypeReference<>() {};

    private static final Set<String> RESTRICTED_REQUEST_HEADERS = Set.of(
        "host", "content-length", "transfer-encoding", "connection",
        "keep-alive", "proxy-connection", "upgrade");
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
        "content-length", "transfer-encoding");

    private HttpMessageUtil() {}

    /** Convert a Netty FullHttpRequest to the Map format expected by IJsonTransformer. */
    public static Map<String, Object> requestToMap(FullHttpRequest request) {
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
            payload.put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, parseJsonOrText(body));
            map.put(JsonKeysForHttpMessage.PAYLOAD_KEY, payload);
        }
        return map;
    }

    /** Convert a transformed Map back to a Netty FullHttpRequest. */
    @SuppressWarnings("unchecked")
    public static FullHttpRequest mapToRequest(Map<String, Object> requestMap) {
        var method = (String) requestMap.get(JsonKeysForHttpMessage.METHOD_KEY);
        var uri = sanitizeUri((String) requestMap.get(JsonKeysForHttpMessage.URI_KEY));
        var body = extractBodyString(requestMap);
        var bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];

        var request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(method),
            uri,
            Unpooled.wrappedBuffer(bodyBytes)
        );

        var headers = (Map<String, Object>) requestMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
        if (headers != null) {
            for (var entry : headers.entrySet()) {
                if (RESTRICTED_REQUEST_HEADERS.contains(entry.getKey().toLowerCase())) continue;
                addHeaderValues(entry.getKey(), entry.getValue(),
                    (name, val) -> request.headers().add(name, val));
            }
        }
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
        return request;
    }

    /** Convert a Netty FullHttpResponse to the Map format expected by IJsonTransformer. */
    public static Map<String, Object> responseToMap(FullHttpResponse response) {
        var map = new LinkedHashMap<String, Object>();
        map.put(JsonKeysForHttpMessage.STATUS_CODE_KEY, response.status().code());
        map.put(JsonKeysForHttpMessage.PROTOCOL_KEY, response.protocolVersion().text());

        var headers = new LinkedHashMap<String, Object>();
        for (var name : response.headers().names()) {
            var values = response.headers().getAll(name);
            headers.put(name, values.size() == 1 ? values.get(0) : values);
        }
        map.put(JsonKeysForHttpMessage.HEADERS_KEY, headers);

        var body = response.content().toString(StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, parseJsonOrText(body));
            map.put(JsonKeysForHttpMessage.PAYLOAD_KEY, payload);
        }
        return map;
    }

    /** Convert a transformed Map back to a Netty FullHttpResponse. */
    @SuppressWarnings("unchecked")
    public static FullHttpResponse mapToResponse(Map<String, Object> responseMap) {
        var statusCode = responseMap.get(JsonKeysForHttpMessage.STATUS_CODE_KEY);
        int code = statusCode instanceof Number ? ((Number) statusCode).intValue() : 200;

        var body = extractBodyString(responseMap);
        var bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];

        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(code),
            Unpooled.wrappedBuffer(bodyBytes)
        );

        var headers = (Map<String, Object>) responseMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
        if (headers != null) {
            for (var entry : headers.entrySet()) {
                if (SKIP_RESPONSE_HEADERS.contains(entry.getKey().toLowerCase())) continue;
                addHeaderValues(entry.getKey(), entry.getValue(),
                    (name, val) -> response.headers().add(name, val));
            }
        }
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
        return response;
    }

    /** Create a simple error response. */
    public static FullHttpResponse errorResponse(HttpResponseStatus status, String message) {
        var body = message.getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        return response;
    }

    @SuppressWarnings("unchecked")
    static String extractBodyString(Map<String, Object> map) {
        var payload = (Map<String, Object>) map.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        if (payload == null) return null;
        // Prefer inlinedJsonSequenceBodies (NDJSON) when present — used by _bulk-style requests.
        // Matches the replayer's NettyJsonBodySerializeHandler semantics: one JSON doc per line
        // with a trailing newline. See OpenSearch _bulk API which requires the trailing newline.
        var ndjsonBodies = payload.get(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY);
        if (ndjsonBodies instanceof List) {
            return serializeNdjson((List<?>) ndjsonBodies);
        }
        // inlinedJsonBody may be a Map, a List (top-level JSON array), or a String (passthrough).
        // Jackson's ObjectMapper handles both Map and List without additional branching.
        var jsonBody = payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY);
        if (jsonBody != null) {
            if (jsonBody instanceof String) return (String) jsonBody;
            try {
                return MAPPER.writeValueAsString(jsonBody);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize inlinedJsonBody", e);
            }
        }
        // Fallback to inlinedTextBody (String)
        return (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
    }

    /**
     * Serialize a list of JSON values as NDJSON: one JSON document per line, each followed by '\n'
     * (including the last one). Matches the OpenSearch _bulk API requirement and is semantically
     * equivalent to the replayer's NettyJsonBodySerializeHandler.serializePayloadList with
     * addLastNewline=true (i.e. no sibling binary/text body present).
     */
    private static String serializeNdjson(List<?> items) {
        var sb = new StringBuilder();
        for (var item : items) {
            try {
                sb.append(MAPPER.writeValueAsString(item)).append('\n');
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize inlinedJsonSequenceBodies item", e);
            }
        }
        return sb.toString();
    }

    /**
     * Parse a JSON string into a structured value for efficient Map/List access by transforms.
     * - Objects (starting with '{') → LinkedHashMap
     * - Arrays (starting with '[')  → List<Object>  (matches replayer JsonAccumulator semantics)
     * - Anything else or parse failure → raw String (passthrough)
     *
     * Storing a top-level array under INLINED_JSON_BODY_DOCUMENT_KEY is aligned with the replayer's
     * NettyJsonBodyAccumulateHandler, which stores a single top-level JSON value (Map or Object[])
     * under the same key. Only a stream of multiple top-level JSON values (true NDJSON) maps to
     * INLINED_NDJSON_BODIES_DOCUMENT_KEY — that ingress path is not handled here (see LIMITATIONS
     * shortcode NDJSON-INGEST-PARITY).
     */
    private static Object parseJsonOrText(String body) {
        if (body.isEmpty()) return body;
        var first = body.charAt(0);
        if (first == '{') {
            try {
                return MAPPER.readValue(body, MAP_TYPE_REF);
            } catch (JsonProcessingException ignored) {
                return body;
            }
        }
        if (first == '[') {
            try {
                return MAPPER.readValue(body, LIST_TYPE_REF);
            } catch (JsonProcessingException ignored) {
                return body;
            }
        }
        return body;
    }

    /** Write a response, handling keep-alive semantics. */
    public static void writeResponse(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /** Send an error response. */
    public static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status,
                                  String message, boolean keepAlive) {
        writeResponse(ctx, errorResponse(status, message), keepAlive);
    }

    private static void addHeaderValues(String name, Object value,
                                        java.util.function.BiConsumer<String, String> adder) {
        if (value instanceof List) {
            for (var v : (List<?>) value) {
                adder.accept(name, v.toString());
            }
        } else {
            adder.accept(name, value.toString());
        }
    }

    /**
     * Sanitize the URI by parsing and reconstructing only the path and query components.
     * Rejects absolute URIs to prevent SSRF since the target host is configured at startup.
     */
    static String sanitizeUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        var parsed = URI.create(uri);
        if (parsed.getHost() != null) {
            throw new IllegalArgumentException("Absolute URIs are not allowed: " + uri);
        }
        var path = parsed.getRawPath();
        var query = parsed.getRawQuery();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return query != null ? path + "?" + query : path;
    }
}
