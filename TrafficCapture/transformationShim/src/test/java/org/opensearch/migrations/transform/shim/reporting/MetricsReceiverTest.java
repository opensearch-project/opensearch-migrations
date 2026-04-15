package org.opensearch.migrations.transform.shim.reporting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.shim.validation.TargetResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsReceiverTest {

    private static final MetricsExtractor SOLR_EXTRACTOR = new SolrMetricsExtractor();

    /** Simple in-memory sink for testing. */
    static class CapturingSink implements ReportingSink {
        final List<ValidationDocument> documents = new ArrayList<>();
        @Override public void submit(ValidationDocument document) { documents.add(document); }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private Map<String, Object> solrResponseBody(long numFound, int qTime) {
        return Map.of(
            "response", Map.of("numFound", numFound),
            "responseHeader", Map.of("QTime", qTime)
        );
    }

    private Map<String, Object> requestMap(String method, String uri) {
        return Map.of("method", method, "URI", uri, "headers", Map.of("Host", "localhost"));
    }

    private TargetResponse successResponse(String name, Map<String, Object> parsedBody) {
        return new TargetResponse(name, 200, new byte[0], parsedBody,
            Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
    }

    private Map<String, Map<String, Object>> emptyPerTargetMetrics() {
        return new LinkedHashMap<>();
    }

    @Test
    void collectExtractsHitCountsAndLatency() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/mycore/select", Map.of("Host", "localhost"), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/mycore/_search", Map.of(), null);
        var baseline = successResponse("solr", solrResponseBody(100, 12));
        var candidate = successResponse("opensearch", solrResponseBody(95, 15));
        collector.process(original, transformed, baseline, candidate, Map.of());

        assertEquals(1, sink.documents.size());
        var doc = sink.documents.get(0);
        assertEquals(100L, doc.baselineHitCount());
        assertEquals(95L, doc.candidateHitCount());
        assertEquals(5.0, doc.hitCountDriftPercentage());
        assertEquals(12L, doc.baselineResponseTimeMs());
        assertEquals(15L, doc.candidateResponseTimeMs());
        assertEquals(3L, doc.responseTimeDeltaMs());
        assertEquals("mycore", doc.collectionName());
        assertEquals("/solr/{collection}/select", doc.normalizedEndpoint());
    }

    @Test
    void collectPopulatesOriginalRequest() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/core1/select?q=*:*", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/core1/_search", Map.of(), null);
        var baseline = successResponse("solr", solrResponseBody(10, 1));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(original, transformed, baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNotNull(doc.originalRequest());
        assertEquals("GET", doc.originalRequest().method());
        assertEquals("/solr/core1/select?q=*:*", doc.originalRequest().uri());
        assertNull(doc.originalRequest().body());
    }

    @Test
    void requestBodyIncludedWhenFlagTrue() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, true);
        var reqMap = Map.<String, Object>of("method", "POST", "URI", "/solr/c/update",
            "headers", Map.of(), "payload", "{\"add\":{}}");
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNotNull(sink.documents.get(0).originalRequest().body());
        assertEquals("{\"add\":{}}", sink.documents.get(0).originalRequest().body());
    }

    @Test
    void requestBodyExcludedWhenFlagFalse() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var reqMap = Map.<String, Object>of("method", "POST", "URI", "/solr/c/update",
            "headers", Map.of(), "payload", "{\"add\":{}}");
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNull(sink.documents.get(0).originalRequest().body());
    }

    @Test
    void nullResponsesProduceNullFields() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        collector.process(original, transformed, null, null, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.baselineHitCount());
        assertNull(doc.candidateHitCount());
        assertNull(doc.hitCountDriftPercentage());
    }

    @Test
    void customMetricsEmptyWhenNoneEmitted() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = successResponse("solr", Map.of());
        var candidate = successResponse("opensearch", Map.of());
        collector.process(original, transformed, baseline, candidate, null);

        assertTrue(sink.documents.get(0).customMetrics().isEmpty());
    }

    @Test
    void customMetricsMergedFromMultipleTargets() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = successResponse("solr", Map.of());
        var candidate = successResponse("opensearch", Map.of());
        var mergedMetrics = new LinkedHashMap<String, Object>();
        mergedMetrics.put("solr-metric", 1);
        mergedMetrics.put("os-metric", 2);
        collector.process(original, transformed, baseline, candidate, mergedMetrics);

        var doc = sink.documents.get(0);
        assertEquals(1, doc.customMetrics().get("solr-metric"));
        assertEquals(2, doc.customMetrics().get("os-metric"));
    }

    @Test
    void exceptionInCollectDoesNotPropagate() {
        var throwingSink = new ReportingSink() {
            @Override public void submit(ValidationDocument d) { throw new RuntimeException("boom"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var collector = new MetricsReceiver(throwingSink, SOLR_EXTRACTOR, false);
        assertDoesNotThrow(() ->
            collector.process(requestMap("GET", "/solr/c/select"), null, Map.of(), emptyPerTargetMetrics()));
    }

    @Test
    void guardSkipsWhenResponseCountIsNotTwo() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        // Only 1 response — should skip
        var responses = Map.of("solr", successResponse("solr", solrResponseBody(10, 1)));
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/os/_search"));
        collector.process(requestMap("GET", "/solr/c/select"), transformedReqs, responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void guardSkipsWhenTransformedRequestCountIsNotOne() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        // No transformed requests — should skip
        collector.process(requestMap("GET", "/solr/c/select"), emptyPerTargetMetrics(), responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void guardSkipsWhenResponsesNull() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        collector.process(requestMap("GET", "/solr/c/select"), null, null, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void guardSkipsWhenTransformedRequestsNull() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        collector.process(requestMap("GET", "/solr/c/select"), null, responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void mapOverloadResolvesAndDelegatesToResolvedOverload() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(50, 5)),
            "opensearch", successResponse("opensearch", solrResponseBody(48, 7))
        );
        var perTargetMetrics = new LinkedHashMap<String, Map<String, Object>>();
        perTargetMetrics.put("opensearch", Map.of("custom-key", 42));
        collector.process(requestMap("GET", "/solr/c/select"), transformedReqs, responses, perTargetMetrics);

        assertEquals(1, sink.documents.size());
        var doc = sink.documents.get(0);
        assertEquals(50L, doc.baselineHitCount());
        assertEquals(48L, doc.candidateHitCount());
        assertEquals(42, doc.customMetrics().get("custom-key"));
    }

    @Test
    void mapOverloadWithNullPerTargetMetrics() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        collector.process(requestMap("GET", "/solr/c/select"), transformedReqs, responses, null);

        assertEquals(1, sink.documents.size());
        assertTrue(sink.documents.get(0).customMetrics().isEmpty());
    }

    @Test
    void nullOriginalRequestProducesNullUri() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var baseline = successResponse("solr", solrResponseBody(10, 1));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(null, null, baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.collectionName());
        assertNull(doc.normalizedEndpoint());
    }

    @Test
    void buildRequestRecordHandlesNonStringAndNonMapValues() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        // method is Integer, URI is Integer, headers is a String — all non-matching types
        var reqMap = Map.<String, Object>of("method", 123, "URI", 456, "headers", "not-a-map");
        var transformedReqs = Map.of("opensearch", reqMap);
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        var doc = sink.documents.get(0);
        assertNull(doc.originalRequest().method());
        assertNull(doc.originalRequest().uri());
        assertNull(doc.originalRequest().headers());
    }

    @Test
    void objectPayloadSerializedAsJson() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, true);
        var payload = Map.of("key", "value");
        var reqMap = new LinkedHashMap<String, Object>();
        reqMap.put("method", "POST");
        reqMap.put("URI", "/solr/c/update");
        reqMap.put("headers", Map.of());
        reqMap.put("payload", payload);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        var body = sink.documents.get(0).originalRequest().body();
        assertNotNull(body);
        assertTrue(body.contains("key"));
        assertTrue(body.contains("value"));
    }

    @Test
    void nullPayloadProducesNullBody() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, true);
        var reqMap = new LinkedHashMap<String, Object>();
        reqMap.put("method", "POST");
        reqMap.put("URI", "/solr/c/update");
        reqMap.put("headers", Map.of());
        reqMap.put("payload", null);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNull(sink.documents.get(0).originalRequest().body());
    }

    @Test
    void extractLongReturnsNullWhenFieldMissing() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        // parsedBody has no "response.numFound" path
        var baseline = successResponse("solr", Map.of("other", "data"));
        var candidate = successResponse("opensearch", Map.of("other", "data"));
        collector.process(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.baselineHitCount());
        assertNull(doc.candidateHitCount());
    }

    @Test
    void exceptionInResolvedCollectDoesNotPropagate() {
        var throwingSink = new ReportingSink() {
            @Override public void submit(ValidationDocument d) { throw new RuntimeException("boom"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var collector = new MetricsReceiver(throwingSink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = successResponse("solr", solrResponseBody(10, 1));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        assertDoesNotThrow(() -> collector.process(original, transformed, baseline, candidate, Map.of()));
    }

    @Test
    void exceptionInMapOverloadDoesNotPropagate() {
        var throwingSink = new ReportingSink() {
            @Override public void submit(ValidationDocument d) { throw new RuntimeException("boom"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var collector = new MetricsReceiver(throwingSink, SOLR_EXTRACTOR, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        assertDoesNotThrow(() ->
            collector.process(requestMap("GET", "/solr/c/select"), transformedReqs, responses, emptyPerTargetMetrics()));
    }

    @Test
    void nonEmptyFacetComparisonsIncluded() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        // Build response bodies with facet_counts so FacetComparator produces non-empty comparisons
        var facets = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("category", List.of("books", 10, "movies", 5))));
        var baseline = successResponse("solr", facets);
        var secondaryFacets = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("category", List.of("books", 8, "movies", 5))));
        var candidate = successResponse("opensearch", secondaryFacets);
        collector.process(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNotNull(doc.comparisons());
        assertFalse(doc.comparisons().isEmpty());
    }

    @Test
    void includeRequestBodyTrueButNoPayloadKey() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, true);
        // No "payload" key in the request map
        var reqMap = Map.<String, Object>of("method", "GET", "URI", "/solr/c/select", "headers", Map.of());
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNull(sink.documents.get(0).originalRequest().body());
    }

    @Test
    void extractLongReturnsNullForErrorResponse() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var baseline = TargetResponse.error("solr", Duration.ofMillis(10), new RuntimeException("fail"));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.baselineHitCount());
        assertNull(doc.baselineResponseTimeMs());
    }

    @Test
    void resolvedOverloadWorksDirectly() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = successResponse("solr", solrResponseBody(100, 12));
        var candidate = successResponse("opensearch", solrResponseBody(95, 15));
        collector.process(original, transformed, baseline, candidate, Map.of("warn", 1));

        assertEquals(1, sink.documents.size());
        var doc = sink.documents.get(0);
        assertEquals(100L, doc.baselineHitCount());
        assertEquals(95L, doc.candidateHitCount());
        assertNotNull(doc.transformedRequest());
        assertEquals("/c/_search", doc.transformedRequest().uri());
        assertEquals(1, doc.customMetrics().get("warn"));
    }

    @Test
    void toJsonStringFallsBackToToStringOnSerializationFailure() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, true);
        // InputStream is not serializable by Jackson and will throw
        var unserializable = new java.io.InputStream() {
            @Override public int read() { return -1; }
            @Override public String toString() { return "fallback-value"; }
        };
        var reqMap = new LinkedHashMap<String, Object>();
        reqMap.put("method", "POST");
        reqMap.put("URI", "/solr/c/update");
        reqMap.put("headers", Map.of());
        reqMap.put("payload", unserializable);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertEquals("fallback-value", sink.documents.get(0).originalRequest().body());
    }

    @Test
    void buildRequestRecordWithNullMap() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        assertNull(collector.buildRequestRecord(null));
    }

    @Test
    void guardSkipsWhenMultipleTransformedRequests() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        // Two transformed requests — should skip (expects exactly 1)
        var transformedReqs = Map.of(
            "opensearch", Map.<String, Object>of("method", "GET", "URI", "/a"),
            "extra", Map.<String, Object>of("method", "GET", "URI", "/b")
        );
        collector.process(requestMap("GET", "/solr/c/select"), transformedReqs, responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void extractLongReturnsNullWhenParsedBodyNull() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        // Success response but with null parsedBody
        var baseline = new TargetResponse("solr", 200, new byte[0], null,
            Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
        var candidate = new TargetResponse("opensearch", 200, new byte[0], null,
            Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
        collector.process(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.baselineHitCount());
        assertNull(doc.candidateHitCount());
        assertNull(doc.baselineResponseTimeMs());
        assertNull(doc.candidateResponseTimeMs());
    }

    @Test
    void stringPayloadReturnedDirectly() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, true);
        var reqMap = new LinkedHashMap<String, Object>();
        reqMap.put("method", "POST");
        reqMap.put("URI", "/solr/c/update");
        reqMap.put("headers", Map.of());
        reqMap.put("payload", "raw-string-body");
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.process(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertEquals("raw-string-body", sink.documents.get(0).originalRequest().body());
    }
}

// --- Response capture tests ---

class MetricsReceiverResponseTest {

    private static final MetricsExtractor SOLR_EXTRACTOR = new SolrMetricsExtractor();

    static class CapturingSink implements ReportingSink {
        final List<ValidationDocument> documents = new ArrayList<>();
        @Override public void submit(ValidationDocument document) { documents.add(document); }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private Map<String, Object> solrResponseBody(long numFound, int qTime) {
        return Map.of(
            "response", Map.of("numFound", numFound),
            "responseHeader", Map.of("QTime", qTime)
        );
    }

    private TargetResponse successResponse(String name, Map<String, Object> parsedBody) {
        return new TargetResponse(name, 200, new byte[0], parsedBody,
            Duration.ofMillis(50), Duration.ofMillis(5), Duration.ofMillis(10), null);
    }

    @Test
    void responseRecordsPopulatedWithStatusAndLatency() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = successResponse("solr", solrResponseBody(10, 1));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(original, transformed, baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNotNull(doc.baselineResponse());
        assertNotNull(doc.candidateResponse());
        assertEquals(200, doc.baselineResponse().statusCode());
        assertEquals(200, doc.candidateResponse().statusCode());
        assertNull(doc.baselineResponse().error());
        assertNull(doc.candidateResponse().error());
    }

    @Test
    void responseRecordCapturesErrorMessage() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = TargetResponse.error("solr", Duration.ofMillis(10), new RuntimeException("connection refused"));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(original, transformed, baseline, candidate, Map.of());

        var doc = sink.documents.get(0);
        assertNotNull(doc.baselineResponse());
        assertEquals(-1, doc.baselineResponse().statusCode());
        assertEquals("connection refused", doc.baselineResponse().error());
        assertNull(doc.candidateResponse().error());
    }

    @Test
    void nullResponseProducesNullResponseRecord() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        collector.process(original, transformed, null, null, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.baselineResponse());
        assertNull(doc.candidateResponse());
    }

    @Test
    void responseBodyExcludedByDefault() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = successResponse("solr", solrResponseBody(10, 1));
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(original, transformed, baseline, candidate, Map.of());

        assertNull(sink.documents.get(0).baselineResponse().body());
        assertNull(sink.documents.get(0).candidateResponse().body());
    }

    @Test
    void responseBodyIncludedWhenFlagTrue() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false, true);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        // Realistic Solr response with docs and facets
        var solrBody = Map.<String, Object>of(
            "responseHeader", Map.of("status", 0, "QTime", 5),
            "response", Map.of("numFound", 2, "start", 0,
                "docs", List.of(
                    Map.of("id", "doc-1", "title", "First Document"),
                    Map.of("id", "doc-2", "title", "Second Document"))),
            "facet_counts", Map.of("facet_fields", Map.of("category", List.of("books", 10, "movies", 5)))
        );
        var osBody = Map.<String, Object>of(
            "responseHeader", Map.of("status", 0, "QTime", 7),
            "response", Map.of("numFound", 2, "start", 0,
                "docs", List.of(
                    Map.of("id", "doc-1", "title", "First Document"),
                    Map.of("id", "doc-2", "title", "Second Document")))
        );
        var baseline = successResponse("solr", solrBody);
        var candidate = successResponse("opensearch", osBody);
        collector.process(original, transformed, baseline, candidate, Map.of());

        var baselineBody = sink.documents.get(0).baselineResponse().body();
        var candidateBody = sink.documents.get(0).candidateResponse().body();
        assertNotNull(baselineBody);
        assertNotNull(candidateBody);
        // Verify actual document content is captured
        assertTrue(baselineBody.contains("First Document"));
        assertTrue(baselineBody.contains("doc-2"));
        assertTrue(baselineBody.contains("facet_counts"));
        assertTrue(candidateBody.contains("Second Document"));
        assertFalse(candidateBody.contains("facet_counts")); // OS response had no facets
    }

    @Test
    void responseBodyNullWhenParsedBodyNull() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false, true);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var baseline = new TargetResponse("solr", 200, new byte[0], null,
            Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null);
        var candidate = successResponse("opensearch", solrResponseBody(10, 1));
        collector.process(original, transformed, baseline, candidate, Map.of());

        assertNull(sink.documents.get(0).baselineResponse().body());
    }

    @Test
    void mapOverloadPopulatesResponseRecords() {
        var sink = new CapturingSink();
        var collector = new MetricsReceiver(sink, SOLR_EXTRACTOR, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(50, 5)),
            "opensearch", successResponse("opensearch", solrResponseBody(48, 7))
        );
        collector.process(
            Map.<String, Object>of("method", "GET", "URI", "/solr/c/select", "headers", Map.of()),
            transformedReqs, responses, new LinkedHashMap<>());

        var doc = sink.documents.get(0);
        assertNotNull(doc.baselineResponse());
        assertNotNull(doc.candidateResponse());
        assertEquals(200, doc.baselineResponse().statusCode());
        assertEquals(200, doc.candidateResponse().statusCode());
    }
}
