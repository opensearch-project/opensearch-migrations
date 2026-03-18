package org.opensearch.migrations.transform.shim.reporting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.shim.validation.TargetResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    /** Simple in-memory sink for testing. */
    static class CapturingSink implements MetricsSink {
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
        var collector = new MetricsCollector(sink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/mycore/select", Map.of("Host", "localhost"), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/mycore/_search", Map.of(), null);
        var primary = successResponse("solr", solrResponseBody(100, 12));
        var secondary = successResponse("opensearch", solrResponseBody(95, 15));
        collector.collect(original, transformed, primary, secondary, Map.of());

        assertEquals(1, sink.documents.size());
        var doc = sink.documents.get(0);
        assertEquals(100L, doc.solrHitCount());
        assertEquals(95L, doc.opensearchHitCount());
        assertEquals(5.0, doc.hitCountDriftPercentage());
        assertEquals(12L, doc.solrQtimeMs());
        assertEquals(15L, doc.opensearchTookMs());
        assertEquals(3L, doc.queryTimeDeltaMs());
        assertEquals("mycore", doc.collectionName());
        assertEquals("/solr/{collection}/select", doc.normalizedEndpoint());
    }

    @Test
    void collectPopulatesOriginalRequest() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/core1/select?q=*:*", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/core1/_search", Map.of(), null);
        var primary = successResponse("solr", solrResponseBody(10, 1));
        var secondary = successResponse("opensearch", solrResponseBody(10, 1));
        collector.collect(original, transformed, primary, secondary, Map.of());

        var doc = sink.documents.get(0);
        assertNotNull(doc.originalRequest());
        assertEquals("GET", doc.originalRequest().method());
        assertEquals("/solr/core1/select?q=*:*", doc.originalRequest().uri());
        assertNull(doc.originalRequest().body());
    }

    @Test
    void requestBodyIncludedWhenFlagTrue() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, true);
        var reqMap = Map.<String, Object>of("method", "POST", "URI", "/solr/c/update",
            "headers", Map.of(), "payload", "{\"add\":{}}");
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.collect(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNotNull(sink.documents.get(0).originalRequest().body());
        assertEquals("{\"add\":{}}", sink.documents.get(0).originalRequest().body());
    }

    @Test
    void requestBodyExcludedWhenFlagFalse() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var reqMap = Map.<String, Object>of("method", "POST", "URI", "/solr/c/update",
            "headers", Map.of(), "payload", "{\"add\":{}}");
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "POST", "URI", "/c/_bulk"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.collect(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNull(sink.documents.get(0).originalRequest().body());
    }

    @Test
    void nullResponsesProduceNullFields() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        collector.collect(original, transformed, null, null, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.solrHitCount());
        assertNull(doc.opensearchHitCount());
        assertNull(doc.hitCountDriftPercentage());
    }

    @Test
    void customMetricsEmptyWhenNoneEmitted() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var primary = successResponse("solr", Map.of());
        var secondary = successResponse("opensearch", Map.of());
        collector.collect(original, transformed, primary, secondary, null);

        assertTrue(sink.documents.get(0).customMetrics().isEmpty());
    }

    @Test
    void customMetricsMergedFromMultipleTargets() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var primary = successResponse("solr", Map.of());
        var secondary = successResponse("opensearch", Map.of());
        var mergedMetrics = new LinkedHashMap<String, Object>();
        mergedMetrics.put("solr-metric", 1);
        mergedMetrics.put("os-metric", 2);
        collector.collect(original, transformed, primary, secondary, mergedMetrics);

        var doc = sink.documents.get(0);
        assertEquals(1, doc.customMetrics().get("solr-metric"));
        assertEquals(2, doc.customMetrics().get("os-metric"));
    }

    @Test
    void exceptionInCollectDoesNotPropagate() {
        var throwingSink = new MetricsSink() {
            @Override public void submit(ValidationDocument d) { throw new RuntimeException("boom"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var collector = new MetricsCollector(throwingSink, false);
        assertDoesNotThrow(() ->
            collector.collect(requestMap("GET", "/solr/c/select"), null, Map.of(), emptyPerTargetMetrics()));
    }

    @Test
    void guardSkipsWhenResponseCountIsNotTwo() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        // Only 1 response — should skip
        var responses = Map.of("solr", successResponse("solr", solrResponseBody(10, 1)));
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/os/_search"));
        collector.collect(requestMap("GET", "/solr/c/select"), transformedReqs, responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void guardSkipsWhenTransformedRequestCountIsNotOne() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        // No transformed requests — should skip
        collector.collect(requestMap("GET", "/solr/c/select"), emptyPerTargetMetrics(), responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void guardSkipsWhenResponsesNull() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        collector.collect(requestMap("GET", "/solr/c/select"), null, null, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void guardSkipsWhenTransformedRequestsNull() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        collector.collect(requestMap("GET", "/solr/c/select"), null, responses, emptyPerTargetMetrics());
        assertTrue(sink.documents.isEmpty());
    }

    @Test
    void mapOverloadResolvesAndDelegatesToResolvedOverload() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(50, 5)),
            "opensearch", successResponse("opensearch", solrResponseBody(48, 7))
        );
        var perTargetMetrics = new LinkedHashMap<String, Map<String, Object>>();
        perTargetMetrics.put("opensearch", Map.of("custom-key", 42));
        collector.collect(requestMap("GET", "/solr/c/select"), transformedReqs, responses, perTargetMetrics);

        assertEquals(1, sink.documents.size());
        var doc = sink.documents.get(0);
        assertEquals(50L, doc.solrHitCount());
        assertEquals(48L, doc.opensearchHitCount());
        assertEquals(42, doc.customMetrics().get("custom-key"));
    }

    @Test
    void mapOverloadWithNullPerTargetMetrics() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        collector.collect(requestMap("GET", "/solr/c/select"), transformedReqs, responses, null);

        assertEquals(1, sink.documents.size());
        assertTrue(sink.documents.get(0).customMetrics().isEmpty());
    }

    @Test
    void nullOriginalRequestProducesNullUri() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var primary = successResponse("solr", solrResponseBody(10, 1));
        var secondary = successResponse("opensearch", solrResponseBody(10, 1));
        collector.collect(null, null, primary, secondary, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.collectionName());
        assertNull(doc.normalizedEndpoint());
    }

    @Test
    void buildRequestRecordHandlesNonStringAndNonMapValues() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        // method is Integer, URI is Integer, headers is a String — all non-matching types
        var reqMap = Map.<String, Object>of("method", 123, "URI", 456, "headers", "not-a-map");
        var transformedReqs = Map.of("opensearch", reqMap);
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.collect(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        var doc = sink.documents.get(0);
        assertNull(doc.originalRequest().method());
        assertNull(doc.originalRequest().uri());
        assertNull(doc.originalRequest().headers());
    }

    @Test
    void objectPayloadSerializedAsJson() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, true);
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
        collector.collect(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        var body = sink.documents.get(0).originalRequest().body();
        assertNotNull(body);
        assertTrue(body.contains("key"));
        assertTrue(body.contains("value"));
    }

    @Test
    void nullPayloadProducesNullBody() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, true);
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
        collector.collect(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNull(sink.documents.get(0).originalRequest().body());
    }

    @Test
    void extractLongReturnsNullWhenFieldMissing() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        // parsedBody has no "response.numFound" path
        var primary = successResponse("solr", Map.of("other", "data"));
        var secondary = successResponse("opensearch", Map.of("other", "data"));
        collector.collect(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            primary, secondary, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.solrHitCount());
        assertNull(doc.opensearchHitCount());
    }

    @Test
    void exceptionInResolvedCollectDoesNotPropagate() {
        var throwingSink = new MetricsSink() {
            @Override public void submit(ValidationDocument d) { throw new RuntimeException("boom"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var collector = new MetricsCollector(throwingSink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var primary = successResponse("solr", solrResponseBody(10, 1));
        var secondary = successResponse("opensearch", solrResponseBody(10, 1));
        assertDoesNotThrow(() -> collector.collect(original, transformed, primary, secondary, Map.of()));
    }

    @Test
    void exceptionInMapOverloadDoesNotPropagate() {
        var throwingSink = new MetricsSink() {
            @Override public void submit(ValidationDocument d) { throw new RuntimeException("boom"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var collector = new MetricsCollector(throwingSink, false);
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", solrResponseBody(10, 1)),
            "opensearch", successResponse("opensearch", solrResponseBody(10, 1))
        );
        assertDoesNotThrow(() ->
            collector.collect(requestMap("GET", "/solr/c/select"), transformedReqs, responses, emptyPerTargetMetrics()));
    }

    @Test
    void nonEmptyFacetComparisonsIncluded() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        // Build response bodies with facet_counts so FacetComparator produces non-empty comparisons
        var facets = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("category", List.of("books", 10, "movies", 5))));
        var primary = successResponse("solr", facets);
        var secondaryFacets = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("category", List.of("books", 8, "movies", 5))));
        var secondary = successResponse("opensearch", secondaryFacets);
        collector.collect(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            primary, secondary, Map.of());

        var doc = sink.documents.get(0);
        assertNotNull(doc.comparisons());
        assertFalse(doc.comparisons().isEmpty());
    }

    @Test
    void includeRequestBodyTrueButNoPayloadKey() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, true);
        // No "payload" key in the request map
        var reqMap = Map.<String, Object>of("method", "GET", "URI", "/solr/c/select", "headers", Map.of());
        var transformedReqs = Map.of("opensearch", Map.<String, Object>of("method", "GET", "URI", "/c/_search"));
        var responses = Map.of(
            "solr", successResponse("solr", Map.of()),
            "opensearch", successResponse("opensearch", Map.of())
        );
        collector.collect(reqMap, transformedReqs, responses, emptyPerTargetMetrics());

        assertNull(sink.documents.get(0).originalRequest().body());
    }

    @Test
    void extractLongReturnsNullForErrorResponse() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var primary = TargetResponse.error("solr", Duration.ofMillis(10), new RuntimeException("fail"));
        var secondary = successResponse("opensearch", solrResponseBody(10, 1));
        collector.collect(
            new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null),
            new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null),
            primary, secondary, Map.of());

        var doc = sink.documents.get(0);
        assertNull(doc.solrHitCount());
        assertNull(doc.solrQtimeMs());
    }

    @Test
    void resolvedOverloadWorksDirectly() {
        var sink = new CapturingSink();
        var collector = new MetricsCollector(sink, false);
        var original = new ValidationDocument.RequestRecord("GET", "/solr/c/select", Map.of(), null);
        var transformed = new ValidationDocument.RequestRecord("GET", "/c/_search", Map.of(), null);
        var primary = successResponse("solr", solrResponseBody(100, 12));
        var secondary = successResponse("opensearch", solrResponseBody(95, 15));
        collector.collect(original, transformed, primary, secondary, Map.of("warn", 1));

        assertEquals(1, sink.documents.size());
        var doc = sink.documents.get(0);
        assertEquals(100L, doc.solrHitCount());
        assertEquals(95L, doc.opensearchHitCount());
        assertNotNull(doc.transformedRequest());
        assertEquals("/c/_search", doc.transformedRequest().uri());
        assertEquals(1, doc.customMetrics().get("warn"));
    }
}
