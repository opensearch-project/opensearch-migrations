package org.opensearch.migrations.transform.shim.reporting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertEquals(Set.of("music"), entry.missingKeys());
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
        assertEquals(Set.of("music"), entry.extraKeys());
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
        assertEquals(Set.of("books"), results.get(0).missingKeys());
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

    @Test
    void facetCountsNotAMap() {
        var body = Map.<String, Object>of("facet_counts", "not-a-map");
        var results = FacetComparator.compareFacets(body, body);
        assertTrue(results.isEmpty());
    }

    @Test
    void facetFieldsNotAMap() {
        var body = Map.<String, Object>of("facet_counts", Map.of("facet_fields", "not-a-map"));
        var results = FacetComparator.compareFacets(body, body);
        assertTrue(results.isEmpty());
    }

    @Test
    void parseBucketsSkipsNullKeyAndNonNumericCount() {
        // Odd-length list: last element has no pair, should be skipped
        var solr = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("f", List.of("a", 1, "b", "notANumber"))));
        var os = Map.<String, Object>of("facet_counts",
            Map.of("facet_fields", Map.of("f", List.of("a", 1))));
        var results = FacetComparator.compareFacets(solr, os);
        assertEquals(1, results.size());
        // "b" should be in missing keys since its count wasn't a Number and was skipped from solr buckets
        var entry = results.get(0);
        assertTrue(entry.keysMatch()); // only "a" parsed from both sides
    }

    @Test
    void facetsOnlyOnSecondSide() {
        var os = facetBody("category", "books", 10);
        var results = FacetComparator.compareFacets(Map.of(), os);
        assertEquals(1, results.size());
        assertFalse(results.get(0).keysMatch());
        assertEquals(Set.of("books"), results.get(0).extraKeys());
    }

    // ---- JSON Facet tests ----

    @SafeVarargs
    private static Map<String, Object> jsonFacetBody(String facetName, Map<String, Object>... buckets) {
        List<Map<String, Object>> bucketList = new ArrayList<>();
        for (Map<String, Object> b : buckets) {
            bucketList.add(b);
        }
        Map<String, Object> facetValue = new HashMap<>();
        facetValue.put("buckets", bucketList);

        Map<String, Object> facets = new HashMap<>();
        facets.put("count", 9999);
        facets.put(facetName, facetValue);

        Map<String, Object> body = new HashMap<>();
        body.put("facets", facets);
        return body;
    }

    private static Map<String, Object> bucket(String val, Number count) {
        Map<String, Object> m = new HashMap<>();
        m.put("val", val);
        m.put("count", count);
        return m;
    }

    /** Filter results to only json_facet entries. */
    private static List<ComparisonEntry> jsonFacetEntries(List<ComparisonEntry> all) {
        return all.stream().filter(e -> "json_facet".equals(e.type())).toList();
    }

    @Test
    void compareJsonFacets_matchingBuckets() {
        var baseline = jsonFacetBody("cat", bucket("books", 100), bucket("music", 50));
        var candidate = jsonFacetBody("cat", bucket("books", 100), bucket("music", 50));
        var results = jsonFacetEntries(FacetComparator.compareFacets(baseline, candidate));
        assertEquals(1, results.size());
        ComparisonEntry entry = results.get(0);
        assertEquals("json_facet", entry.type());
        assertTrue(entry.keysMatch());
        assertNull(entry.missingKeys());
        assertNull(entry.extraKeys());
    }

    @Test
    void compareJsonFacets_missingBuckets() {
        var baseline = jsonFacetBody("cat", bucket("books", 100), bucket("music", 50));
        var candidate = jsonFacetBody("cat", bucket("books", 100));
        var results = jsonFacetEntries(FacetComparator.compareFacets(baseline, candidate));
        ComparisonEntry entry = results.get(0);
        assertFalse(entry.keysMatch());
        assertEquals(Set.of("music"), entry.missingKeys());
        assertNull(entry.extraKeys());
    }

    @Test
    void compareJsonFacets_extraBuckets() {
        var baseline = jsonFacetBody("cat", bucket("books", 100));
        var candidate = jsonFacetBody("cat", bucket("books", 100), bucket("music", 50));
        var results = jsonFacetEntries(FacetComparator.compareFacets(baseline, candidate));
        ComparisonEntry entry = results.get(0);
        assertFalse(entry.keysMatch());
        assertNull(entry.missingKeys());
        assertEquals(Set.of("music"), entry.extraKeys());
    }

    @Test
    void compareJsonFacets_valueDrift() {
        var baseline = jsonFacetBody("cat", bucket("books", 100));
        var candidate = jsonFacetBody("cat", bucket("books", 90));
        var results = jsonFacetEntries(FacetComparator.compareFacets(baseline, candidate));
        var drifts = results.get(0).valueDrifts();
        assertNotNull(drifts);
        assertEquals(1, drifts.size());
        assertEquals("books", drifts.get(0).key());
        assertEquals(10.0, drifts.get(0).driftPercentage());
    }

    @Test
    void compareJsonFacets_multipleNamedFacets() {
        Map<String, Object> facets = new HashMap<>();
        facets.put("count", 500);
        facets.put("cat", Map.of("buckets", List.of(bucket("books", 10))));
        facets.put("author", Map.of("buckets", List.of(bucket("Smith", 5))));
        Map<String, Object> body = Map.of("facets", facets);

        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> "json_facet".equals(e.type())));
        var names = results.stream().map(ComparisonEntry::name).toList();
        assertTrue(names.contains("cat"));
        assertTrue(names.contains("author"));
    }

    @Test
    void compareJsonFacets_countKeyExcluded() {
        var body = jsonFacetBody("cat", bucket("books", 10));
        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(1, results.size());
        assertEquals("cat", results.get(0).name());
    }

    @Test
    void compareJsonFacets_nullFacetsKey() {
        var results = jsonFacetEntries(FacetComparator.compareFacets(Map.of(), Map.of()));
        assertTrue(results.isEmpty());
    }

    @Test
    void compareJsonFacets_facetsNotAMap() {
        var body = Map.<String, Object>of("facets", "not-a-map");
        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertTrue(results.isEmpty());
    }

    @Test
    void compareJsonFacets_noBucketsKey() {
        Map<String, Object> facets = new HashMap<>();
        facets.put("cat", Map.of("something", "else"));
        Map<String, Object> body = Map.of("facets", facets);

        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(1, results.size());
        assertTrue(results.get(0).keysMatch());
        assertNull(results.get(0).missingKeys());
        assertNull(results.get(0).extraKeys());
    }

    @Test
    void compareJsonFacets_bucketsNotAList() {
        Map<String, Object> facets = new HashMap<>();
        facets.put("cat", Map.of("buckets", "not-a-list"));
        Map<String, Object> body = Map.of("facets", facets);

        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(1, results.size());
        assertTrue(results.get(0).keysMatch());
    }

    @Test
    void compareJsonFacets_bucketMissingVal() {
        Map<String, Object> badBucket = new HashMap<>();
        badBucket.put("count", 10);

        Map<String, Object> facets = new HashMap<>();
        facets.put("cat", Map.of("buckets", List.of(badBucket)));
        Map<String, Object> body = Map.of("facets", facets);

        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(1, results.size());
        assertTrue(results.get(0).keysMatch());
    }

    @Test
    void compareJsonFacets_bucketCountNotNumber() {
        Map<String, Object> badBucket = new HashMap<>();
        badBucket.put("val", "books");
        badBucket.put("count", "not-a-number");

        Map<String, Object> facets = new HashMap<>();
        facets.put("cat", Map.of("buckets", List.of(badBucket)));
        Map<String, Object> body = Map.of("facets", facets);

        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(1, results.size());
        assertTrue(results.get(0).keysMatch());
    }

    @Test
    void compareJsonFacets_emptyBucketsArray() {
        Map<String, Object> facets = new HashMap<>();
        facets.put("cat", Map.of("buckets", List.of()));
        Map<String, Object> body = Map.of("facets", facets);

        var results = jsonFacetEntries(FacetComparator.compareFacets(body, body));
        assertEquals(1, results.size());
        assertTrue(results.get(0).keysMatch());
        assertNull(results.get(0).valueDrifts());
    }

    @Test
    void compareJsonFacets_onlyBaselineHasJsonFacets() {
        var baseline = jsonFacetBody("cat", bucket("books", 10), bucket("music", 5));
        var results = jsonFacetEntries(FacetComparator.compareFacets(baseline, Map.of()));
        assertEquals(1, results.size());
        assertFalse(results.get(0).keysMatch());
        assertEquals(Set.of("books", "music"), results.get(0).missingKeys());
    }

    @Test
    void compareJsonFacets_onlyCandidateHasJsonFacets() {
        var candidate = jsonFacetBody("cat", bucket("books", 10), bucket("music", 5));
        var results = jsonFacetEntries(FacetComparator.compareFacets(Map.of(), candidate));
        assertEquals(1, results.size());
        assertFalse(results.get(0).keysMatch());
        assertEquals(Set.of("books", "music"), results.get(0).extraKeys());
    }

    @Test
    void compareJsonFacets_bothBodiesNull() {
        assertTrue(FacetComparator.compareFacets(null, null).isEmpty());
    }
}
