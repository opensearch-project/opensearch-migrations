package org.opensearch.migrations.transform.shim.reporting;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.opensearch.migrations.transform.shim.reporting.ValidationDocument.RequestRecord;
import org.opensearch.migrations.transform.shim.validation.TargetResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts comparison metrics from per-request target responses
 * and submits a ValidationDocument to the configured MetricsSink.
 * Called from the Netty event loop — must not block.
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetricsSink sink;
    private final boolean includeRequestBody;

    public MetricsCollector(MetricsSink sink, boolean includeRequestBody) {
        this.sink = sink;
        this.includeRequestBody = includeRequestBody;
    }

    /**
     * Entry point called from MultiTargetRoutingHandler.
     * Validates preconditions, resolves primary/secondary targets, and delegates
     * to the overload that takes resolved arguments.
     * Never throws — all exceptions are caught and logged.
     */
    public void collect(
            Map<String, Object> originalRequestMap,
            Map<String, Map<String, Object>> perTargetTransformedRequests,
            Map<String, TargetResponse> responses,
            Map<String, Map<String, Object>> perTargetTransformMetrics
    ) {
        try {
            // Guard: need exactly 2 responses (primary + secondary)
            if (responses == null || responses.size() != 2) {
                log.debug("Skipping metrics collection: expected 2 target responses, got {}",
                        responses != null ? responses.size() : 0);
                return;
            }

            // Guard: need exactly 1 transformed request (the secondary target)
            if (perTargetTransformedRequests == null || perTargetTransformedRequests.size() != 1) {
                log.debug("Skipping metrics collection: expected 1 transformed request, got {}",
                        perTargetTransformedRequests != null ? perTargetTransformedRequests.size() : 0);
                return;
            }

            // Identify secondary target (the one with a transformed request)
            String secondaryName = perTargetTransformedRequests.keySet().iterator().next();
            Map<String, Object> transformedReqMap = perTargetTransformedRequests.get(secondaryName);

            // Identify primary target (the other one)
            TargetResponse primaryResponse = null;
            TargetResponse secondaryResponse = null;
            for (var entry : responses.entrySet()) {
                if (entry.getKey().equals(secondaryName)) {
                    secondaryResponse = entry.getValue();
                } else {
                    primaryResponse = entry.getValue();
                }
            }

            // Build request records
            RequestRecord originalRequest = buildRequestRecord(originalRequestMap);
            RequestRecord transformedRequest = buildRequestRecord(transformedReqMap);

            // Get secondary target's transform metrics
            Map<String, Object> secondaryMetrics = perTargetTransformMetrics != null
                    ? perTargetTransformMetrics.getOrDefault(secondaryName, Map.of())
                    : Map.of();

            collect(originalRequest, transformedRequest, primaryResponse, secondaryResponse, secondaryMetrics);
        } catch (Exception e) {
            log.error("Error collecting validation metrics", e);
        }
    }

    /**
     * Collect metrics from resolved primary and secondary target responses.
     * Never throws — all exceptions are caught and logged.
     *
     * @param originalRequest    the original Solr request
     * @param transformedRequest the transformed request sent to the secondary target
     * @param primaryResponse    the primary target's response
     * @param secondaryResponse  the secondary target's response
     * @param transformMetrics   custom metrics emitted by the secondary target's transforms
     */
    public void collect(
            RequestRecord originalRequest,
            RequestRecord transformedRequest,
            TargetResponse primaryResponse,
            TargetResponse secondaryResponse,
            Map<String, Object> transformMetrics
    ) {
        try {
            String uri = originalRequest != null ? originalRequest.uri() : null;
            String collectionName = MetricsExtractor.extractCollectionName(uri);
            String normalizedEndpoint = MetricsExtractor.normalizeEndpoint(uri);

            Long primaryHitCount = extractLong(primaryResponse, "response.numFound");
            Long secondaryHitCount = extractLong(secondaryResponse, "response.numFound");
            Double hitCountDrift = MetricsExtractor.computeDriftPercentage(primaryHitCount, secondaryHitCount);

            Long primaryQtime = extractLong(primaryResponse, "responseHeader.QTime");
            Long secondaryQtime = extractLong(secondaryResponse, "responseHeader.QTime");
            Long queryTimeDelta = MetricsExtractor.computeQueryTimeDelta(primaryQtime, secondaryQtime);

            List<ValidationDocument.ComparisonEntry> comparisons = FacetComparator.compareFacets(
                    primaryResponse != null ? primaryResponse.parsedBody() : null,
                    secondaryResponse != null ? secondaryResponse.parsedBody() : null);

            Map<String, Object> customMetrics = transformMetrics != null
                    ? new LinkedHashMap<>(transformMetrics) : new LinkedHashMap<>();

            ValidationDocument doc = new ValidationDocument(
                    Instant.now().toString(),
                    UUID.randomUUID().toString(),
                    originalRequest,
                    transformedRequest,
                    collectionName,
                    normalizedEndpoint,
                    primaryHitCount,
                    secondaryHitCount,
                    hitCountDrift,
                    primaryQtime,
                    secondaryQtime,
                    queryTimeDelta,
                    comparisons.isEmpty() ? null : comparisons,
                    customMetrics
            );

            sink.submit(doc);
        } catch (Exception e) {
            log.error("Error collecting validation metrics", e);
        }
    }

    @SuppressWarnings("unchecked")
    RequestRecord buildRequestRecord(Map<String, Object> requestMap) {
        if (requestMap == null) return null;
        String method = requestMap.get("method") instanceof String s ? s : null;
        String uri = requestMap.get("URI") instanceof String s ? s : null;
        Map<String, Object> headers = requestMap.get("headers") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        String body = null;
        if (includeRequestBody && requestMap.containsKey("payload")) {
            Object payload = requestMap.get("payload");
            body = toJsonString(payload);
        }
        return new RequestRecord(method, uri, headers, body);
    }

    private static String toJsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String string) return string;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("Failed to serialize payload to JSON, falling back to toString", e);
            return value.toString();
        }
    }

    private Long extractLong(TargetResponse response, String path) {
        if (response == null || !response.isSuccess() || response.parsedBody() == null) return null;
        Number n = MetricsExtractor.extractNestedField(response.parsedBody(), path);
        return n != null ? n.longValue() : null;
    }
}
