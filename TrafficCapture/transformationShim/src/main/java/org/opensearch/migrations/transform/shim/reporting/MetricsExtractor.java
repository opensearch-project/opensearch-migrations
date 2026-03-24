package org.opensearch.migrations.transform.shim.reporting;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static utility methods for extracting metrics from parsed response bodies
 * and computing drift/delta values.
 */
public final class MetricsExtractor {

    private static final Pattern COLLECTION_PATTERN = Pattern.compile("^/solr/([^/]+)/(.+)$");

    private MetricsExtractor() {}

    /**
     * Walk a dot-delimited path into a nested map, returning the leaf as a Number or null.
     */
    @SuppressWarnings("unchecked")
    public static Number extractNestedField(Map<String, Object> map, String dottedPath) {
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
     * Compute hit count drift percentage.
     * Returns null if either input is null.
     * Returns 0.0 if both are zero.
     * Returns -1.0 if baseline is zero but candidate is non-zero (sentinel).
     */
    public static Double computeDriftPercentage(Long baselineCount, Long candidateCount) {
        if (baselineCount == null || candidateCount == null) return null;
        if (baselineCount == 0L && candidateCount == 0L) return 0.0;
        if (baselineCount == 0L) return -1.0;
        return Math.abs(baselineCount - candidateCount) / (double) baselineCount * 100.0;
    }

    /**
     * Compute response time delta: candidateTime - baselineTime.
     */
    public static Long computeResponseTimeDelta(Long baselineTime, Long candidateTime) {
        if (baselineTime == null || candidateTime == null) return null;
        return candidateTime - baselineTime;
    }

    /**
     * Extract the Solr collection/core name from a URI like /solr/{name}/select.
     * Returns null if the URI doesn't match the expected pattern.
     */
    public static String extractCollectionName(String uri) {
        if (uri == null) return null;
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Matcher m = COLLECTION_PATTERN.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Normalize a URI to a pattern like /solr/{collection}/select.
     * Returns null for non-matching URIs.
     */
    public static String normalizeEndpoint(String uri) {
        if (uri == null) return null;
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        Matcher m = COLLECTION_PATTERN.matcher(path);
        if (!m.matches()) return null;
        return "/solr/{collection}/" + m.group(2);
    }

    /**
     * Merge transform metrics from request and response transforms.
     * Response values take precedence on key conflicts.
     */
    public static Map<String, Object> mergeTransformMetrics(
            Map<String, Object> requestMetrics, Map<String, Object> responseMetrics) {
        Map<String, Object> merged = new HashMap<>();
        if (requestMetrics != null) merged.putAll(requestMetrics);
        if (responseMetrics != null) merged.putAll(responseMetrics);
        return merged;
    }
}
