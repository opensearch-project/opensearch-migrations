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

    // --- computeQueryTimeDelta ---

    @Test
    void delta_normalValues() {
        assertEquals(3L, MetricsExtractor.computeQueryTimeDelta(12L, 15L));
        assertEquals(-5L, MetricsExtractor.computeQueryTimeDelta(20L, 15L));
    }

    @Test
    void delta_nullInputs() {
        assertNull(MetricsExtractor.computeQueryTimeDelta(null, 15L));
        assertNull(MetricsExtractor.computeQueryTimeDelta(12L, null));
    }

    // --- extractCollectionName ---

    @Test
    void collectionName_selectEndpoint() {
        assertEquals("mycore", MetricsExtractor.extractCollectionName("/solr/mycore/select"));
    }

    @Test
    void collectionName_updateEndpoint() {
        assertEquals("my-collection", MetricsExtractor.extractCollectionName("/solr/my-collection/update"));
    }

    @Test
    void collectionName_withQueryString() {
        assertEquals("core1", MetricsExtractor.extractCollectionName("/solr/core1/select?q=*:*&rows=10"));
    }

    @Test
    void collectionName_nonMatching() {
        assertNull(MetricsExtractor.extractCollectionName("/other/path"));
        assertNull(MetricsExtractor.extractCollectionName(null));
    }

    // --- normalizeEndpoint ---

    @Test
    void normalize_selectEndpoint() {
        assertEquals("/solr/{collection}/select", MetricsExtractor.normalizeEndpoint("/solr/mycore/select"));
    }

    @Test
    void normalize_updateEndpoint() {
        assertEquals("/solr/{collection}/update", MetricsExtractor.normalizeEndpoint("/solr/core1/update"));
    }

    @Test
    void normalize_nonMatching() {
        assertNull(MetricsExtractor.normalizeEndpoint("/other/path"));
        assertNull(MetricsExtractor.normalizeEndpoint(null));
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
