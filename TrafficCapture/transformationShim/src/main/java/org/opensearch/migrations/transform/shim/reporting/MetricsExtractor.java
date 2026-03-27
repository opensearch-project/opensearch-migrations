package org.opensearch.migrations.transform.shim.reporting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts comparison metrics from parsed response bodies and computes drift/delta values.
 * Implementations are specific to the source system (e.g. Solr, Elasticsearch).
 * Generic computation helpers are provided as static methods.
 */
public interface MetricsExtractor {

    /**
     * Extract the collection/index name from a request URI.
     * Returns null if the URI doesn't match the expected pattern.
     */
    String extractCollectionName(String uri);

    /**
     * Normalize a URI to a canonical pattern (e.g. /solr/{collection}/select).
     * Returns null for non-matching URIs.
     */
    String normalizeEndpoint(String uri);

    /**
     * Return the dot-delimited path into the parsed response body where the hit count lives.
     * For example, Solr uses "response.numFound".
     */
    String hitCountPath();

    /**
     * Return the dot-delimited path into the parsed response body where the query time lives.
     * For example, Solr uses "responseHeader.QTime".
     */
    String queryTimePath();

    /**
     * Compare structured results (e.g. facets, aggregations) between the baseline and
     * candidate parsed response bodies.
     * Returns an empty list when there is nothing to compare.
     */
    List<ValidationDocument.ComparisonEntry> compareResults(
            Map<String, Object> baselineBody, Map<String, Object> candidateBody);

    // ---- static utility methods (implementation-agnostic) ----

    /**
     * Walk a dot-delimited path into a nested map, returning the leaf as a Number or null.
     */
    @SuppressWarnings("unchecked")
    static Number extractNestedField(Map<String, Object> map, String dottedPath) {
        if (map == null || dottedPath == null) return null;
        String[] segments = dottedPath.split("\\.");
        Object current = map;
        for (String segment : segments) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) return null;
        }
        return current instanceof Number number ? number : null;
    }

    /**
     * Walk a dot-delimited path into a nested map, returning the leaf as a Long or null.
     */
    static Long extractLong(Map<String, Object> map, String dottedPath) {
        Number n = extractNestedField(map, dottedPath);
        return n != null ? n.longValue() : null;
    }

    /**
     * Compute hit count drift percentage.
     * Returns null if either input is null.
     * Returns 0.0 if both are zero.
     * Returns -1.0 if baseline is zero but candidate is non-zero (sentinel).
     */
    static Double computeDriftPercentage(Long baselineCount, Long candidateCount) {
        if (baselineCount == null || candidateCount == null) return null;
        if (baselineCount == 0L && candidateCount == 0L) return 0.0;
        if (baselineCount == 0L) return -1.0;
        return Math.abs(baselineCount - candidateCount) / (double) baselineCount * 100.0;
    }

    /**
     * Compute response time delta: candidateTime - baselineTime.
     */
    static Long computeResponseTimeDelta(Long baselineTime, Long candidateTime) {
        if (baselineTime == null || candidateTime == null) return null;
        return candidateTime - baselineTime;
    }

    /**
     * Merge transform metrics from request and response transforms.
     * Response values take precedence on key conflicts.
     */
    static Map<String, Object> mergeTransformMetrics(
            Map<String, Object> requestMetrics, Map<String, Object> responseMetrics) {
        Map<String, Object> merged = new HashMap<>();
        if (requestMetrics != null) merged.putAll(requestMetrics);
        if (responseMetrics != null) merged.putAll(responseMetrics);
        return merged;
    }
}
