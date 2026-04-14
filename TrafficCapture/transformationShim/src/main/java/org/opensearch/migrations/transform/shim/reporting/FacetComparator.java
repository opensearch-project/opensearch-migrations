package org.opensearch.migrations.transform.shim.reporting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ComparisonEntry;
import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.ValueDrift;

/**
 * Compares facet/aggregation bucket results between two post-transform
 * response bodies. Supports both Solr flat facets ({@code facet_counts.facet_fields})
 * and JSON Facets API responses ({@code facets} with nested bucket objects).
 *
 * <p>Both formats are normalised into a common {@code Map<String, Map<String, Number>>}
 * representation (facet name → bucket key → count) before comparison, so the
 * downstream diff logic runs exactly once per facet regardless of source format.
 */
public final class FacetComparator {

    private FacetComparator() {}

    /**
     * Compare all facets (flat and JSON) from both parsed bodies.
     * Returns empty list when neither response has any facets.
     */
    public static List<ComparisonEntry> compareFacets(
            Map<String, Object> baselineBody, Map<String, Object> candidateBody) {
        List<ComparisonEntry> entries = new ArrayList<>();
        entries.addAll(compareFlatFacets(baselineBody, candidateBody));
        entries.addAll(compareJsonFacets(baselineBody, candidateBody));
        return entries;
    }

    private static List<ComparisonEntry> compareFlatFacets(
            Map<String, Object> baselineBody, Map<String, Object> candidateBody) {
        Map<String, Map<String, Number>> baseline = normaliseFlatFacets(baselineBody);
        Map<String, Map<String, Number>> candidate = normaliseFlatFacets(candidateBody);
        return compareNormalisedFacets("facet_field", baseline, candidate);
    }

    /**
     * Normalise flat Solr facets into facetName → (bucketKey → count).
     * Each field value is an alternating [key, count, key, count, ...] array.
     */
    private static Map<String, Map<String, Number>> normaliseFlatFacets(Map<String, Object> parsedBody) {
        Map<String, Object> fields = extractFacetFields(parsedBody);
        Map<String, Map<String, Number>> normalised = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            normalised.put(e.getKey(), parseFlatBuckets(e.getValue()));
        }
        return normalised;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Number> parseFlatBuckets(Object value) {
        if (!(value instanceof List)) return Collections.emptyMap();
        List<Object> list = (List<Object>) value;
        Map<String, Number> buckets = new LinkedHashMap<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            Object key = list.get(i);
            Object count = list.get(i + 1);
            if (key != null && count instanceof Number number) {
                buckets.put(key.toString(), number);
            }
        }
        return buckets;
    }

    private static List<ComparisonEntry> compareJsonFacets(
            Map<String, Object> baselineBody, Map<String, Object> candidateBody) {
        Map<String, Map<String, Number>> baseline = normaliseJsonFacets(baselineBody);
        Map<String, Map<String, Number>> candidate = normaliseJsonFacets(candidateBody);
        return compareNormalisedFacets("json_facet", baseline, candidate);
    }

    /**
     * Normalise JSON API facets into facetName → (bucketKey → count).
     * Excludes the top-level "count" key which is the total match count.
     */
    private static Map<String, Map<String, Number>> normaliseJsonFacets(Map<String, Object> parsedBody) {
        Map<String, Object> facets = extractJsonFacets(parsedBody);
        Map<String, Map<String, Number>> normalised = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : facets.entrySet()) {
            if ("count".equals(e.getKey())) continue;
            normalised.put(e.getKey(), parseJsonBuckets(e.getValue()));
        }
        return normalised;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractJsonFacets(Map<String, Object> parsedBody) {
        if (parsedBody == null) return Collections.emptyMap();
        Object facets = parsedBody.get("facets");
        if (!(facets instanceof Map)) return Collections.emptyMap();
        return (Map<String, Object>) facets;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Number> parseJsonBuckets(Object facetValue) {
        if (!(facetValue instanceof Map)) return Collections.emptyMap();
        Object bucketsObj = ((Map<String, Object>) facetValue).get("buckets");
        if (!(bucketsObj instanceof List)) return Collections.emptyMap();
        List<Object> bucketsList = (List<Object>) bucketsObj;
        Map<String, Number> buckets = new LinkedHashMap<>();
        for (Object element : bucketsList) {
            if (!(element instanceof Map)) continue;
            Map<String, Object> bucket = (Map<String, Object>) element;
            Object val = bucket.get("val");
            Object count = bucket.get("count");
            if (val != null && count instanceof Number number) {
                buckets.put(val.toString(), number);
            }
        }
        return buckets;
    }

    /**
     * Compare two normalised facet maps and produce one {@link ComparisonEntry} per
     * facet name found in either side.
     */
    private static List<ComparisonEntry> compareNormalisedFacets(
            String type,
            Map<String, Map<String, Number>> baseline,
            Map<String, Map<String, Number>> candidate) {
        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(baseline.keySet());
        allNames.addAll(candidate.keySet());

        List<ComparisonEntry> entries = new ArrayList<>();
        for (String name : allNames) {
            Map<String, Number> bBuckets = baseline.getOrDefault(name, Collections.emptyMap());
            Map<String, Number> cBuckets = candidate.getOrDefault(name, Collections.emptyMap());
            entries.add(buildComparisonEntry(type, name, bBuckets, cBuckets));
        }
        return entries;
    }

    private static ComparisonEntry buildComparisonEntry(
            String type, String name,
            Map<String, Number> baselineBuckets, Map<String, Number> candidateBuckets) {
        Set<String> baselineKeys = baselineBuckets.keySet();
        Set<String> candidateKeys = candidateBuckets.keySet();

        boolean keysMatch = baselineKeys.equals(candidateKeys);
        Set<String> missingKeys = new LinkedHashSet<>(baselineKeys);
        missingKeys.removeAll(candidateKeys);
        Set<String> extraKeys = new LinkedHashSet<>(candidateKeys);
        extraKeys.removeAll(baselineKeys);

        List<ValueDrift> valueDrifts = new ArrayList<>();
        for (String key : baselineKeys) {
            if (candidateBuckets.containsKey(key)) {
                Number bv = baselineBuckets.get(key);
                Number cv = candidateBuckets.get(key);
                Double drift = MetricsExtractor.computeDriftPercentage(
                        bv != null ? bv.longValue() : null,
                        cv != null ? cv.longValue() : null);
                valueDrifts.add(new ValueDrift(key, bv, cv, drift));
            }
        }

        return new ComparisonEntry(type, name, keysMatch,
                missingKeys.isEmpty() ? null : missingKeys,
                extraKeys.isEmpty() ? null : extraKeys,
                valueDrifts.isEmpty() ? null : valueDrifts);
    }
}
