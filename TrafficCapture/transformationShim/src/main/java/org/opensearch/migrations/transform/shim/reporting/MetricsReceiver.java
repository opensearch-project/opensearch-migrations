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
 * and submits a ValidationDocument to the configured ReportingSink.
 * Called from the Netty event loop — must not block.
 */
public class MetricsReceiver {

    private static final Logger log = LoggerFactory.getLogger(MetricsReceiver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReportingSink sink;
    private final MetricsExtractor extractor;
    private final boolean includeRequestBody;
    private final boolean includeResponseBody;

    public MetricsReceiver(ReportingSink sink, MetricsExtractor extractor, boolean includeRequestBody) {
        this(sink, extractor, includeRequestBody, false);
    }

    public MetricsReceiver(ReportingSink sink, MetricsExtractor extractor,
                           boolean includeRequestBody, boolean includeResponseBody) {
        this.sink = sink;
        this.extractor = extractor;
        this.includeRequestBody = includeRequestBody;
        this.includeResponseBody = includeResponseBody;
    }

    /**
     * Entry point called from MultiTargetRoutingHandler.
     * Validates preconditions, resolves baseline/candidate targets, and delegates
     * to the overload that takes resolved arguments.
     * Never throws — all exceptions are caught and logged.
     */
    public void process(
            Map<String, Object> originalRequestMap,
            Map<String, Map<String, Object>> perTargetTransformedRequests,
            Map<String, TargetResponse> responses,
            Map<String, Map<String, Object>> perTargetTransformMetrics
    ) {
        try {
            // Guard: need exactly 2 responses (baseline + candidate)
            if (responses == null || responses.size() != 2) {
                log.debug("Skipping metrics collection: expected 2 target responses, got {}",
                        responses != null ? responses.size() : 0);
                return;
            }

            // Guard: need exactly 1 transformed request (the candidate target)
            if (perTargetTransformedRequests == null || perTargetTransformedRequests.size() != 1) {
                log.debug("Skipping metrics collection: expected 1 transformed request, got {}",
                        perTargetTransformedRequests != null ? perTargetTransformedRequests.size() : 0);
                return;
            }

            // Identify candidate target (the one with a transformed request)
            String candidateName = perTargetTransformedRequests.keySet().iterator().next();
            Map<String, Object> transformedReqMap = perTargetTransformedRequests.get(candidateName);

            // Identify baseline target (the other one)
            TargetResponse baselineResponse = null;
            TargetResponse candidateResponse = null;
            for (var entry : responses.entrySet()) {
                if (entry.getKey().equals(candidateName)) {
                    candidateResponse = entry.getValue();
                } else {
                    baselineResponse = entry.getValue();
                }
            }

            // Build request records
            RequestRecord originalRequest = buildRequestRecord(originalRequestMap);
            RequestRecord transformedRequest = buildRequestRecord(transformedReqMap);

            // Get candidate target's transform metrics
            Map<String, Object> candidateMetrics = perTargetTransformMetrics != null
                    ? perTargetTransformMetrics.getOrDefault(candidateName, Map.of())
                    : Map.of();

            process(originalRequest, transformedRequest, baselineResponse, candidateResponse, candidateMetrics);
        } catch (Exception e) {
            log.error("Error collecting validation metrics", e);
        }
    }

    /**
     * Collect metrics from resolved baseline and candidate target responses.
     * Never throws — all exceptions are caught and logged.
     *
     * @param originalRequest    the original source request
     * @param transformedRequest the transformed request sent to the candidate target
     * @param baselineResponse   the baseline target's response
     * @param candidateResponse  the candidate target's response
     * @param transformMetrics   custom metrics emitted by the candidate target's transforms
     */
    public void process(
            RequestRecord originalRequest,
            RequestRecord transformedRequest,
            TargetResponse baselineResponse,
            TargetResponse candidateResponse,
            Map<String, Object> transformMetrics
    ) {
        try {
            String uri = originalRequest != null ? originalRequest.uri() : null;
            String collectionName = extractor.extractCollectionName(uri);
            String normalizedEndpoint = extractor.normalizeEndpoint(uri);

            var baselineBody = baselineResponse != null && baselineResponse.isSuccess() ? baselineResponse.parsedBody() : null;
            var candidateBody = candidateResponse != null && candidateResponse.isSuccess() ? candidateResponse.parsedBody() : null;

            Long baselineHitCount = MetricsExtractor.extractLong(baselineBody, extractor.hitCountPath());
            Long candidateHitCount = MetricsExtractor.extractLong(candidateBody, extractor.hitCountPath());
            Double hitCountDrift = MetricsExtractor.computeDriftPercentage(baselineHitCount, candidateHitCount);

            Long baselineQtime = MetricsExtractor.extractLong(baselineBody, extractor.queryTimePath());
            Long candidateQtime = MetricsExtractor.extractLong(candidateBody, extractor.queryTimePath());
            Long responseTimeDelta = MetricsExtractor.computeResponseTimeDelta(baselineQtime, candidateQtime);

            List<ValidationDocument.ComparisonEntry> comparisons = extractor.compareResults(
                    baselineBody, candidateBody);

            Map<String, Object> customMetrics = transformMetrics != null
                    ? new LinkedHashMap<>(transformMetrics) : new LinkedHashMap<>();

            ValidationDocument.ResponseRecord baselineResponseRecord = buildResponseRecord(baselineResponse);
            ValidationDocument.ResponseRecord candidateResponseRecord = buildResponseRecord(candidateResponse);

            ValidationDocument doc = new ValidationDocument(
                    Instant.now().toString(),
                    UUID.randomUUID().toString().substring(0, 8),
                    originalRequest,
                    transformedRequest,
                    collectionName,
                    normalizedEndpoint,
                    baselineHitCount,
                    candidateHitCount,
                    hitCountDrift,
                    baselineQtime,
                    candidateQtime,
                    responseTimeDelta,
                    comparisons.isEmpty() ? null : comparisons,
                    baselineResponseRecord,
                    candidateResponseRecord,
                    customMetrics
            );

            sink.submit(doc);
        } catch (Exception e) {
            log.error("Error collecting validation metrics", e);
        }
    }

    ValidationDocument.ResponseRecord buildResponseRecord(TargetResponse response) {
        if (response == null) return null;
        String errorMsg = response.error() != null ? response.error().getMessage() : null;
        String body = null;
        if (includeResponseBody && response.parsedBody() != null) {
            body = toJsonString(response.parsedBody());
        }
        return new ValidationDocument.ResponseRecord(
                response.statusCode(),
                errorMsg,
                body
        );
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
            log.warn("Failed to serialize payload to JSON, falling back to toString", e);
            return value.toString();
        }
    }

}
