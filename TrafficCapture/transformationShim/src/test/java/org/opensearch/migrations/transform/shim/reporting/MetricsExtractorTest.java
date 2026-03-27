package org.opensearch.migrations.transform.shim.reporting;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsExtractorTest {

    // --- extractNestedField ---

    @Test
    void extractNestedField_validPath() {
        Map<String, Object> map = Map.of("response", Map.of("numFound", 42));
        assertEquals(42, MetricsExtractor.extractNestedField(map, "response.numFound"));
    }

    @Test
    void extractNestedField_deepPath() {
        Map<String, Object> map = Map.of("hits", Map.of("total", Map.of("value", 100)));
        assertEquals(100, MetricsExtractor.extractNestedField(map, "hits.total.value"));
    }

    @Test
    void extractNestedField_missingIntermediate() {
        Map<String, Object> map = Map.of("response", Map.of("docs", 1));
        assertNull(MetricsExtractor.extractNestedField(map, "response.numFound"));
    }

    @Test
    void extractNestedField_nullMap() {
        assertNull(MetricsExtractor.extractNestedField(null, "response.numFound"));
    }

    @Test
    void extractNestedField_nonNumericLeaf() {
        Map<String, Object> map = Map.of("response", Map.of("numFound", "notANumber"));
        assertNull(MetricsExtractor.extractNestedField(map, "response.numFound"));
    }

    @Test
    void extractNestedField_singleSegment() {
        var map = Map.<String, Object>of("took", 15);
        assertEquals(15, MetricsExtractor.extractNestedField(map, "took"));
    }

    @Test
    void extractNestedField_nullPath() {
        var map = Map.<String, Object>of("took", 15);
        assertNull(MetricsExtractor.extractNestedField(map, null));
    }

    // --- computeDriftPercentage ---

    @Test
    void drift_normalValues() {
        assertEquals(10.0, MetricsExtractor.computeDriftPercentage(100L, 90L));
        assertEquals(50.0, MetricsExtractor.computeDriftPercentage(200L, 100L));
    }

    @Test
    void drift_bothZero() {
        assertEquals(0.0, MetricsExtractor.computeDriftPercentage(0L, 0L));
    }

    @Test
    void drift_solrZeroOsNonZero() {
        assertEquals(-1.0, MetricsExtractor.computeDriftPercentage(0L, 5L));
    }

    @Test
    void drift_nullInputs() {
        assertNull(MetricsExtractor.computeDriftPercentage(null, 5L));
        assertNull(MetricsExtractor.computeDriftPercentage(5L, null));
        assertNull(MetricsExtractor.computeDriftPercentage(null, null));
    }

    @Test
    void drift_equalValues() {
        assertEquals(0.0, MetricsExtractor.computeDriftPercentage(100L, 100L));
    }

    // --- computeResponseTimeDelta ---

    @Test
    void delta_normalValues() {
        assertEquals(3L, MetricsExtractor.computeResponseTimeDelta(12L, 15L));
        assertEquals(-5L, MetricsExtractor.computeResponseTimeDelta(20L, 15L));
    }

    @Test
    void delta_nullInputs() {
        assertNull(MetricsExtractor.computeResponseTimeDelta(null, 15L));
        assertNull(MetricsExtractor.computeResponseTimeDelta(12L, null));
    }

    // --- extractLong ---

    @Test
    void extractLong_validPath() {
        var map = Map.<String, Object>of("response", Map.of("numFound", 42));
        assertEquals(42L, MetricsExtractor.extractLong(map, "response.numFound"));
    }

    @Test
    void extractLong_nullMap() {
        assertNull(MetricsExtractor.extractLong(null, "response.numFound"));
    }

    @Test
    void extractLong_missingPath() {
        var map = Map.<String, Object>of("other", 1);
        assertNull(MetricsExtractor.extractLong(map, "response.numFound"));
    }

    // --- mergeTransformMetrics ---

    @Test
    void merge_disjointMaps() {
        var req = Map.<String, Object>of("a", 1);
        var resp = Map.<String, Object>of("b", 2);
        var merged = MetricsExtractor.mergeTransformMetrics(req, resp);
        assertEquals(Map.of("a", 1, "b", 2), merged);
    }

    @Test
    void merge_overlappingKeys_responseWins() {
        var req = Map.<String, Object>of("key", "fromReq");
        var resp = Map.<String, Object>of("key", "fromResp");
        var merged = MetricsExtractor.mergeTransformMetrics(req, resp);
        assertEquals("fromResp", merged.get("key"));
    }

    @Test
    void merge_nullMaps() {
        assertEquals(Map.of(), MetricsExtractor.mergeTransformMetrics(null, null));
        assertEquals(Map.of("a", 1), MetricsExtractor.mergeTransformMetrics(Map.of("a", 1), null));
        assertEquals(Map.of("b", 2), MetricsExtractor.mergeTransformMetrics(null, Map.of("b", 2)));
    }
}
