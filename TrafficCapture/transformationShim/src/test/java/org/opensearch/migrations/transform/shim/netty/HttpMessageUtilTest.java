package org.opensearch.migrations.transform.shim.netty;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpMessageUtil} focused on the ingest/egress behavior for
 * JSON objects, top-level JSON arrays, and NDJSON sequence bodies.
 *
 * <p>Semantics are intentionally aligned with the traffic replayer's
 * {@code NettyJsonBodyAccumulateHandler} (ingress) and {@code NettyJsonBodySerializeHandler}
 * (egress). See {@link JsonKeysForHttpMessage} for key contracts.
 */
class HttpMessageUtilTest {

    // --- Ingress: parseJsonOrText (exercised via requestToMap) ---

    @Test
    void requestToMap_parsesJsonObjectBodyAsMap() {
        var body = "{\"id\":\"1\",\"title\":\"hello\"}";
        var request = buildRequest(body);

        var map = HttpMessageUtil.requestToMap(request);

        var payload = payloadOf(map);
        var inlinedBody = payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY);
        assertInstanceOf(Map.class, inlinedBody, "JSON object body should be parsed as a Map");
        @SuppressWarnings("unchecked")
        var asMap = (Map<String, Object>) inlinedBody;
        assertEquals("1", asMap.get("id"));
        assertEquals("hello", asMap.get("title"));
    }

    @Test
    void requestToMap_parsesTopLevelJsonArrayAsList() {
        // Matches replayer JsonAccumulator behavior: a single top-level JSON array is a
        // valid top-level value and is stored under INLINED_JSON_BODY_DOCUMENT_KEY.
        var body = "[{\"id\":\"1\"},{\"id\":\"2\"}]";
        var request = buildRequest(body);

        var map = HttpMessageUtil.requestToMap(request);

        var payload = payloadOf(map);
        var inlinedBody = payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY);
        assertInstanceOf(List.class, inlinedBody, "Top-level JSON array body should be parsed as a List");
        @SuppressWarnings("unchecked")
        var asList = (List<Object>) inlinedBody;
        assertEquals(2, asList.size());
        assertInstanceOf(Map.class, asList.get(0));
        assertInstanceOf(Map.class, asList.get(1));
    }

    @Test
    void requestToMap_leavesScalarBodyAsRawString() {
        var body = "plain text body";
        var request = buildRequest(body);

        var map = HttpMessageUtil.requestToMap(request);

        var payload = payloadOf(map);
        var inlinedBody = payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY);
        assertEquals("plain text body", inlinedBody);
    }

    @Test
    void requestToMap_returnsRawStringWhenJsonObjectMalformed() {
        var body = "{not valid json";
        var request = buildRequest(body);

        var map = HttpMessageUtil.requestToMap(request);

        var payload = payloadOf(map);
        assertEquals("{not valid json",
            payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
    }

    @Test
    void requestToMap_returnsRawStringWhenJsonArrayMalformed() {
        var body = "[not valid json";
        var request = buildRequest(body);

        var map = HttpMessageUtil.requestToMap(request);

        var payload = payloadOf(map);
        assertEquals("[not valid json",
            payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
    }

    // --- Egress: extractBodyString ---

    @Test
    void extractBodyString_returnsNdjsonFromInlinedJsonSequenceBodies() {
        var indexAction = new LinkedHashMap<String, Object>();
        indexAction.put("_index", "mycore");
        indexAction.put("_id", "1");
        var item1 = new LinkedHashMap<String, Object>();
        item1.put("index", indexAction);
        var item2 = new LinkedHashMap<String, Object>();
        item2.put("title", "hello");
        var map = buildMessageWithPayload(Map.of(
            JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY,
            List.of(item1, item2)
        ));

        var body = HttpMessageUtil.extractBodyString(map);

        assertNotNull(body);
        assertTrue(body.endsWith("\n"),
            "NDJSON body must end with a trailing newline for OpenSearch _bulk");
        var lines = body.split("\n");
        assertEquals(2, lines.length);
        // Compare parsed JSON structures (not raw strings) to avoid coupling on field ordering.
        assertEquals(parse("{\"index\":{\"_index\":\"mycore\",\"_id\":\"1\"}}"), parse(lines[0]));
        assertEquals(parse("{\"title\":\"hello\"}"), parse(lines[1]));
    }

    @Test
    void extractBodyString_ndjsonWithSingleItem_stillHasTrailingNewline() {
        var onlyItem = new LinkedHashMap<String, Object>();
        onlyItem.put("delete", Map.of("_id", "1"));
        var map = buildMessageWithPayload(Map.of(
            JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY,
            List.of(onlyItem)
        ));

        var body = HttpMessageUtil.extractBodyString(map);

        assertNotNull(body);
        assertTrue(body.endsWith("\n"));
        assertEquals(1, body.split("\n").length);
    }

    @Test
    void extractBodyString_ndjsonEmptyListProducesEmptyString() {
        var map = buildMessageWithPayload(Map.of(
            JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY,
            List.of()
        ));

        var body = HttpMessageUtil.extractBodyString(map);

        assertEquals("", body);
    }

    @Test
    void extractBodyString_ndjsonPreferredOverInlinedJsonBody() {
        // Producer invariant: only one of the two keys should be set at a time.
        // If both are present (e.g., a mis-wired transform), NDJSON takes precedence to
        // match the intent of the writer that produced the sequence.
        var ndjsonItem = new LinkedHashMap<String, Object>();
        ndjsonItem.put("index", Map.of("_id", "1"));
        var payload = new LinkedHashMap<String, Object>();
        payload.put(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY, List.of(ndjsonItem));
        payload.put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, Map.of("should", "be_ignored"));
        var map = buildMessageWithPayload(payload);

        var body = HttpMessageUtil.extractBodyString(map);

        assertNotNull(body);
        assertTrue(body.contains("\"index\""));
        assertTrue(!body.contains("should"));
    }

    @Test
    void extractBodyString_serializesInlinedJsonBodyList() {
        // A top-level-array inlinedJsonBody must round-trip through Jackson to a JSON array.
        var list = List.of(Map.of("id", "1"), Map.of("id", "2"));
        var map = buildMessageWithPayload(Map.of(
            JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY,
            list
        ));

        var body = HttpMessageUtil.extractBodyString(map);

        assertNotNull(body);
        assertTrue(body.startsWith("["));
        assertTrue(body.endsWith("]"));
        assertTrue(body.contains("\"id\":\"1\""));
        assertTrue(body.contains("\"id\":\"2\""));
    }

    @Test
    void extractBodyString_returnsNullWhenNoPayload() {
        var map = new LinkedHashMap<String, Object>();
        assertNull(HttpMessageUtil.extractBodyString(map));
    }

    // --- helpers ---

    private static FullHttpRequest buildRequest(String body) {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        return new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            "/test",
            Unpooled.wrappedBuffer(bytes)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> map) {
        var payload = (Map<String, Object>) map.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        assertNotNull(payload, "payload must be present");
        return payload;
    }

    private static Map<String, Object> buildMessageWithPayload(Map<String, Object> payload) {
        var map = new LinkedHashMap<String, Object>();
        map.put(JsonKeysForHttpMessage.PAYLOAD_KEY, new LinkedHashMap<>(payload));
        return map;
    }

    /**
     * Parse a JSON string into a structural Map for equality comparisons that are
     * independent of field-ordering quirks in the producer.
     */
    private static Object parse(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
