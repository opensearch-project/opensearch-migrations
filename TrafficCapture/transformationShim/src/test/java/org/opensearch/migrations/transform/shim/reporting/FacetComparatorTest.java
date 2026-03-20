package org.opensearch.migrations.transform.shim.reporting;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ComparisonEntry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FacetComparatorTest {

    private Map<String, Object> facetBody(String fieldName, Object... keysAndCounts) {
        return Map.of("facet_counts", Map.of("facet_fields", Map.of(fieldName, List.of(keysAndCounts))));
    }

    @Test
    void matchingFacets() {
        var solr = facetBody("category", "books", 10, "music", 5);
        var os = facetBody("category", "books", 10, "music", 5);
        var results = FacetComparator.compareFacets(solr, os);
        assertEquals(1, results.size());
        ComparisonEntry entry = results.get(0);
        assertTrue(entry.keysMatch());
        assertNull(entry.missingKeys());
        assertNull(entry.extraKeys());
    }

    @Test
    void missingBucketsOnOpenSearch() {
        var solr = facetBody("category", "books", 10, "music", 5);
        var os = facetBody("category", "books", 10);
        var results = FacetComparator.compareFacets(solr, os);
        ComparisonEntry entry = results.get(0);
        assertFalse(entry.keysMatch());
        assertEquals(List.of("music"), entry.missingKeys());
        assertNull(entry.extraKeys());
    }

    @Test
    void extraBucketsOnOpenSearch() {
        var solr = facetBody("category", "books", 10);
        var os = facetBody("category", "books", 10, "music", 5);
        var results = FacetComparator.compareFacets(solr, os);
        ComparisonEntry entry = results.get(0);
        assertFalse(entry.keysMatch());
        assertNull(entry.missingKeys());
        assertEquals(List.of("music"), entry.extraKeys());
    }

    @Test
    void valueDriftComputation() {
        var solr = facetBody("category", "books", 100);
        var os = facetBody("category", "books", 90);
        var results = FacetComparator.compareFacets(solr, os);
        var drifts = results.get(0).valueDrifts();
        assertNotNull(drifts);
        assertEquals(1, drifts.size());
        assertEquals("books", drifts.get(0).key());
        assertEquals(10.0, drifts.get(0).driftPercentage());
    }

    @Test
    void noFacetsOnEitherSide() {
        var results = FacetComparator.compareFacets(Map.of(), Map.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void nullBodies() {
        assertTrue(FacetComparator.compareFacets(null, null).isEmpty());
    }

    @Test
    void facetsOnOnlyOneSide() {
        var solr = facetBody("category", "books", 10);
        var results = FacetComparator.compareFacets(solr, Map.of());
        assertEquals(1, results.size());
        assertFalse(results.get(0).keysMatch());
        assertEquals(List.of("books"), results.get(0).missingKeys());
    }

    @Test
    void zeroBucketValues() {
        var solr = facetBody("category", "empty", 0);
        var os = facetBody("category", "empty", 0);
        var results = FacetComparator.compareFacets(solr, os);
        var drifts = results.get(0).valueDrifts();
        assertNotNull(drifts);
        assertEquals(0.0, drifts.get(0).driftPercentage());
    }
}
