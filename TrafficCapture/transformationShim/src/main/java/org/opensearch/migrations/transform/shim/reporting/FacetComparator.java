package org.opensearch.migrations.transform.shim.reporting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ComparisonEntry;
import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ValueDrift;

/**
 * Compares facet/aggregation bucket results between two post-transform
 * Solr-format response bodies. Both targets are expected to be in Solr
 * facet format after the response transform has been applied.
 */
public final class FacetComparator {

    private FacetComparator() {}

    /**
     * Compare facets from both parsed bodies and return a list of comparison entries.
     * Returns empty list when neither response has facets.
     */
    public static List<ComparisonEntry> compareFacets(
            Map<String, Object> solrParsedBody, Map<String, Object> osParsedBody) {
        Map<String, Object> solrFacets = extractFacetFields(solrParsedBody);
        Map<String, Object> osFacets = extractFacetFields(osParsedBody);

        if (solrFacets == null && osFacets == null) return Collections.emptyList();

        if (solrFacets == null) solrFacets = Collections.emptyMap();
        if (osFacets == null) osFacets = Collections.emptyMap();

        Set<String> allFieldNames = new LinkedHashSet<>();
        allFieldNames.addAll(solrFacets.keySet());
        allFieldNames.addAll(osFacets.keySet());

        List<ComparisonEntry> entries = new ArrayList<>();
        for (String fieldName : allFieldNames) {
            entries.add(compareSingleFacetField(fieldName, solrFacets.get(fieldName), osFacets.get(fieldName)));
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractFacetFields(Map<String, Object> parsedBody) {
        Map<String, Object> emptyMap = Collections.emptyMap();
        if (parsedBody == null) return emptyMap;
        Object facetCounts = parsedBody.get("facet_counts");
        if (!(facetCounts instanceof Map)) return emptyMap;
        Object facetFields = ((Map<String, Object>) facetCounts).get("facet_fields");
        if (!(facetFields instanceof Map)) return emptyMap;
        return (Map<String, Object>) facetFields;
    }

    /**
     * Compare a single facet field. Solr facet_fields values are alternating
     * [key, count, key, count, ...] arrays.
     */
    private static ComparisonEntry compareSingleFacetField(
            String fieldName, Object solrValue, Object osValue) {
        Map<String, Number> solrBuckets = parseBuckets(solrValue);
        Map<String, Number> osBuckets = parseBuckets(osValue);

        Set<String> solrKeys = solrBuckets.keySet();
        Set<String> osKeys = osBuckets.keySet();

        boolean keysMatch = solrKeys.equals(osKeys);
        List<String> missingKeys = new ArrayList<>(solrKeys);
        missingKeys.removeAll(osKeys);
        List<String> extraKeys = new ArrayList<>(osKeys);
        extraKeys.removeAll(solrKeys);

        List<ValueDrift> valueDrifts = new ArrayList<>();
        for (String key : solrKeys) {
            if (osBuckets.containsKey(key)) {
                Number sv = solrBuckets.get(key);
                Number ov = osBuckets.get(key);
                Double drift = MetricsExtractor.computeDriftPercentage(
                        sv != null ? sv.longValue() : null,
                        ov != null ? ov.longValue() : null);
                valueDrifts.add(new ValueDrift(key, sv, ov, drift));
            }
        }

        return new ComparisonEntry("facet_field", fieldName, keysMatch,
                missingKeys.isEmpty() ? null : missingKeys,
                extraKeys.isEmpty() ? null : extraKeys,
                valueDrifts.isEmpty() ? null : valueDrifts);
    }

    /**
     * Parse Solr facet_fields alternating array [key, count, key, count, ...]
     * into a key→count map. Returns empty map for null/non-list input.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Number> parseBuckets(Object value) {
        if (!(value instanceof List)) return Collections.emptyMap();
        List<Object> list = (List<Object>) value;
        Map<String, Number> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            Object key = list.get(i);
            Object count = list.get(i + 1);
            if (key != null && count instanceof Number) {
                buckets.put(key.toString(), (Number) count);
            }
        }
        return buckets;
    }
}
