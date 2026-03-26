package org.opensearch.migrations.transform.shim.reporting;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SolrMetricsExtractorTest {

    private final SolrMetricsExtractor solr = new SolrMetricsExtractor();

    // --- extractCollectionName ---

    @Test
    void collectionName_selectEndpoint() {
        assertEquals("mycore", solr.extractCollectionName("/solr/mycore/select"));
    }

    @Test
    void collectionName_updateEndpoint() {
        assertEquals("my-collection", solr.extractCollectionName("/solr/my-collection/update"));
    }

    @Test
    void collectionName_withQueryString() {
        assertEquals("core1", solr.extractCollectionName("/solr/core1/select?q=*:*&rows=10"));
    }

    @Test
    void collectionName_nonMatching() {
        assertNull(solr.extractCollectionName("/other/path"));
        assertNull(solr.extractCollectionName(null));
    }

    // --- normalizeEndpoint ---

    @Test
    void normalize_selectEndpoint() {
        assertEquals("/solr/{collection}/select", solr.normalizeEndpoint("/solr/mycore/select"));
    }

    @Test
    void normalize_updateEndpoint() {
        assertEquals("/solr/{collection}/update", solr.normalizeEndpoint("/solr/core1/update"));
    }

    @Test
    void normalize_nonMatching() {
        assertNull(solr.normalizeEndpoint("/other/path"));
        assertNull(solr.normalizeEndpoint(null));
    }

    // --- path accessors ---

    @Test
    void hitCountPath() {
        assertEquals("response.numFound", solr.hitCountPath());
    }

    @Test
    void queryTimePath() {
        assertEquals("responseHeader.QTime", solr.queryTimePath());
    }

    // --- compareResults ---

    @Test
    void compareResults_withFacets() {
        var baselineFacets = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("category", List.of("books", 10, "movies", 5))));
        var candidateFacets = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("category", List.of("books", 8, "movies", 5))));

        var comparisons = solr.compareResults(baselineFacets, candidateFacets);
        assertFalse(comparisons.isEmpty());
        assertEquals("category", comparisons.get(0).name());
    }

    @Test
    void compareResults_noFacets() {
        var comparisons = solr.compareResults(Map.of(), Map.of());
        assertTrue(comparisons.isEmpty());
    }

    @Test
    void compareResults_nullBodies() {
        var comparisons = solr.compareResults(null, null);
        assertTrue(comparisons.isEmpty());
    }
}
