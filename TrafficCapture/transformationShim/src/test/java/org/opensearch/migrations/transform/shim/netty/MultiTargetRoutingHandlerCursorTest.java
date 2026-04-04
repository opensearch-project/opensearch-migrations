package org.opensearch.migrations.transform.shim.netty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.shim.validation.TargetResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiTargetRoutingHandlerCursorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- helper ---

    private static TargetResponse targetResp(String name, Map<String, Object> parsedBody) {
        byte[] raw;
        try {
            raw = MAPPER.writeValueAsBytes(parsedBody);
        } catch (Exception e) {
            raw = new byte[0];
        }
        return new TargetResponse(
            name, 200, raw, parsedBody, Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null
        );
    }

    private static FullHttpResponse httpResponse(Map<String, Object> body) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        FullHttpResponse resp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes)
        );
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return resp;
    }

    private MultiTargetRoutingHandler handler() {
        return new MultiTargetRoutingHandler(
            Map.of(), "solr", java.util.Set.of(), java.util.List.of(),
            Duration.ofSeconds(5), null, 10485760, new java.util.concurrent.atomic.AtomicInteger()
        );
    }

    // --- extractQueryParam ---

    @Test
    void extractQueryParam_returnsValue() {
        String uri = "/solr/products/select?q=*:*&cursorMark=ABC&wt=json";
        assertEquals("ABC", MultiTargetRoutingHandler.extractQueryParam(uri, "cursorMark"));
    }

    @Test
    void extractQueryParam_returnsNullWhenMissing() {
        String uri = "/solr/products/select?q=*:*&wt=json";
        assertNull(MultiTargetRoutingHandler.extractQueryParam(uri, "cursorMark"));
    }

    @Test
    void extractQueryParam_returnsNullWithNoQuery() {
        assertNull(MultiTargetRoutingHandler.extractQueryParam("/solr/products/select", "cursorMark"));
    }

    @Test
    void extractQueryParam_decodesUrlEncoding() {
        String uri = "/solr/products/select?cursorMark=a%3Db%26c";
        assertEquals("a=b&c", MultiTargetRoutingHandler.extractQueryParam(uri, "cursorMark"));
    }

    // --- replaceQueryParam ---

    @Test
    void replaceQueryParam_replacesValue() {
        String uri = "/select?q=*:*&cursorMark=OLD&wt=json";
        String result = MultiTargetRoutingHandler.replaceQueryParam(uri, "cursorMark", "NEW");
        assertTrue(result.contains("cursorMark=NEW"));
        assertTrue(result.contains("q=*:*"));
        assertTrue(result.contains("wt=json"));
    }

    @Test
    void replaceQueryParam_encodesSpecialChars() {
        String uri = "/select?cursorMark=OLD";
        String result = MultiTargetRoutingHandler.replaceQueryParam(uri, "cursorMark", "a=b");
        assertTrue(result.contains("cursorMark=a%3Db"));
    }

    // --- rewriteCursorMarkForTarget ---

    @Test
    void rewriteCursorMark_skipsInitialToken() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("URI", "/select?cursorMark=*&wt=json");
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "opensearch");
        // URI unchanged for cursorMark=*
        assertTrue(((String) req.get("URI")).contains("cursorMark=*"));
    }

    @Test
    void rewriteCursorMark_extractsSolrToken() throws Exception {
        Map<String, String> combined = Map.of("solr", "SOLR_TOKEN", "os", "OS_TOKEN");
        String encoded = Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(combined));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("URI", "/select?cursorMark=" + encoded + "&wt=json");
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "solr");

        String newCursor = MultiTargetRoutingHandler.extractQueryParam((String) req.get("URI"), "cursorMark");
        assertEquals("SOLR_TOKEN", newCursor);
    }

    @Test
    void rewriteCursorMark_extractsOsToken() throws Exception {
        Map<String, String> combined = Map.of("solr", "SOLR_TOKEN", "os", "OS_TOKEN");
        String encoded = Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(combined));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("URI", "/select?cursorMark=" + encoded + "&wt=json");
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "opensearch");

        String newCursor = MultiTargetRoutingHandler.extractQueryParam((String) req.get("URI"), "cursorMark");
        assertEquals("OS_TOKEN", newCursor);
    }

    @Test
    void rewriteCursorMark_passesThroughNonCombinedToken() {
        Map<String, Object> req = new LinkedHashMap<>();
        String plainToken = Base64.getEncoder().encodeToString("[15.25,\"6\"]".getBytes(StandardCharsets.UTF_8));
        req.put("URI", "/select?cursorMark=" + plainToken + "&wt=json");
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "opensearch");
        // URI unchanged — not a combined token
        assertTrue(((String) req.get("URI")).contains(plainToken));
    }

    // --- mergeCursorTokens ---

    @Test
    void mergeCursorTokens_combinesBothTokens() throws Exception {
        Map<String, Object> solrBody = new LinkedHashMap<>();
        solrBody.put("responseHeader", Map.of());
        solrBody.put("response", Map.of("numFound", 0, "docs", java.util.List.of()));
        solrBody.put("nextCursorMark", "SOLR_NATIVE_TOKEN");

        Map<String, Object> osBody = new LinkedHashMap<>();
        osBody.put("responseHeader", Map.of());
        osBody.put("response", Map.of("numFound", 0, "docs", java.util.List.of()));
        osBody.put("nextCursorMark", "OS_ENCODED_TOKEN");

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        FullHttpResponse primary = httpResponse(solrBody);
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, null);

        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);

        String combinedToken = (String) resultBody.get("nextCursorMark");
        String decoded = new String(Base64.getDecoder().decode(combinedToken), StandardCharsets.UTF_8);
        Map<String, Object> tokenMap = MAPPER.readValue(decoded, Map.class);

        assertEquals("SOLR_NATIVE_TOKEN", tokenMap.get("solr"));
        assertEquals("OS_ENCODED_TOKEN", tokenMap.get("os"));
        assertEquals(1, tokenMap.get("v"));
        result.release();
    }

    @Test
    void mergeCursorTokens_skipsWhenOnlyOneToken() throws Exception {
        Map<String, Object> solrBody = new LinkedHashMap<>();
        solrBody.put("nextCursorMark", "SOLR_TOKEN");

        Map<String, Object> osBody = new LinkedHashMap<>();
        // No nextCursorMark in OS response

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        FullHttpResponse primary = httpResponse(solrBody);
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, null);

        // Should return original response unchanged
        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);
        assertEquals("SOLR_TOKEN", resultBody.get("nextCursorMark"));
        result.release();
    }

    @Test
    void mergeCursorTokens_detectsEndOfPagination() throws Exception {
        // When both targets return the same tokens that were sent, pagination has ended
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("solr", "S1");
        combined.put("os", "O1");
        String sentCursorMark = Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(combined));

        Map<String, Object> solrBody = Map.of("nextCursorMark", "S1");
        Map<String, Object> osBody = Map.of("nextCursorMark", "O1");

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        Map<String, Object> originalBody = new LinkedHashMap<>();
        originalBody.put("nextCursorMark", "S1");
        FullHttpResponse primary = httpResponse(originalBody);
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, sentCursorMark);

        // Should return original (no merge) since pagination ended
        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);
        assertEquals("S1", resultBody.get("nextCursorMark"));
        result.release();
    }

    @Test
    void mergeCursorTokens_mergesWhenTokensChanged() throws Exception {
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("solr", "S1");
        combined.put("os", "O1");
        String sentCursorMark = Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(combined));

        Map<String, Object> solrBody = Map.of("nextCursorMark", "S2");
        Map<String, Object> osBody = Map.of("nextCursorMark", "O2");

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        FullHttpResponse primary = httpResponse(Map.of("nextCursorMark", "S2"));
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, sentCursorMark);

        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);
        String decoded = new String(Base64.getDecoder().decode((String) resultBody.get("nextCursorMark")), StandardCharsets.UTF_8);
        Map<String, Object> tokenMap = MAPPER.readValue(decoded, Map.class);
        assertEquals("S2", tokenMap.get("solr"));
        assertEquals("O2", tokenMap.get("os"));
        result.release();
    }

    @Test
    void mergeCursorTokens_handlesInitialCursorMark() throws Exception {
        // sentCursorMark=* should not trigger end detection
        Map<String, Object> solrBody = Map.of("nextCursorMark", "S1");
        Map<String, Object> osBody = Map.of("nextCursorMark", "O1");

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        FullHttpResponse primary = httpResponse(Map.of("nextCursorMark", "S1"));
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, "*");

        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);
        String decoded = new String(Base64.getDecoder().decode((String) resultBody.get("nextCursorMark")), StandardCharsets.UTF_8);
        Map<String, Object> tokenMap = MAPPER.readValue(decoded, Map.class);
        assertEquals("S1", tokenMap.get("solr"));
        assertEquals("O1", tokenMap.get("os"));
        result.release();
    }

    @Test
    void mergeCursorTokens_handlesInvalidSentCursorMark() throws Exception {
        // Non-base64 sentCursorMark should not crash, should proceed with merge
        Map<String, Object> solrBody = Map.of("nextCursorMark", "S1");
        Map<String, Object> osBody = Map.of("nextCursorMark", "O1");

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        FullHttpResponse primary = httpResponse(Map.of("nextCursorMark", "S1"));
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, "not-valid-base64!!!");

        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);
        // Should still merge since the invalid token falls through
        String decoded = new String(Base64.getDecoder().decode((String) resultBody.get("nextCursorMark")), StandardCharsets.UTF_8);
        Map<String, Object> tokenMap = MAPPER.readValue(decoded, Map.class);
        assertEquals("S1", tokenMap.get("solr"));
        result.release();
    }

    @Test
    void mergeCursorTokens_skipsWhenBothTokensMissing() throws Exception {
        Map<String, Object> solrBody = Map.of("response", Map.of());
        Map<String, Object> osBody = Map.of("response", Map.of());

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        Map<String, Object> originalBody = Map.of("response", Map.of());
        FullHttpResponse primary = httpResponse(originalBody);
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, null);

        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);
        assertNull(resultBody.get("nextCursorMark"));
        result.release();
    }

    // --- rewriteCursorMarkForTarget edge cases ---

    @Test
    void rewriteCursorMark_skipsWhenNoUri() {
        Map<String, Object> req = new LinkedHashMap<>();
        // No URI key
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "opensearch");
        assertNull(req.get("URI"));
    }

    @Test
    void rewriteCursorMark_skipsWhenNoCursorMark() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("URI", "/select?q=*:*&wt=json");
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "opensearch");
        assertEquals("/select?q=*:*&wt=json", req.get("URI"));
    }

    @Test
    void rewriteCursorMark_handlesInvalidBase64() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("URI", "/select?cursorMark=not-valid!!&wt=json");
        // Should not throw, just pass through
        MultiTargetRoutingHandler.rewriteCursorMarkForTarget(req, "opensearch");
        assertTrue(((String) req.get("URI")).contains("not-valid!!"));
    }

    @Test
    void mergeCursorTokens_preservesResponseBody() throws Exception {
        Map<String, Object> solrBody = new LinkedHashMap<>();
        solrBody.put("response", Map.of("numFound", 8, "docs", java.util.List.of()));
        solrBody.put("nextCursorMark", "S1");

        Map<String, Object> osBody = new LinkedHashMap<>();
        osBody.put("nextCursorMark", "O1");

        Map<String, TargetResponse> allResponses = new LinkedHashMap<>();
        allResponses.put("solr", targetResp("solr", solrBody));
        allResponses.put("opensearch", targetResp("opensearch", osBody));

        FullHttpResponse primary = httpResponse(solrBody);
        FullHttpResponse result = handler().mergeCursorTokens(primary, allResponses, null);

        byte[] resultBytes = new byte[result.content().readableBytes()];
        result.content().readBytes(resultBytes);
        Map<String, Object> resultBody = MAPPER.readValue(resultBytes, Map.class);

        // Original fields preserved
        Map<String, Object> resp = (Map<String, Object>) resultBody.get("response");
        assertEquals(8, resp.get("numFound"));

        // Content-Length header updated
        assertEquals(
            String.valueOf(resultBytes.length),
            result.headers().get(HttpHeaderNames.CONTENT_LENGTH)
        );
        result.release();
    }
}
