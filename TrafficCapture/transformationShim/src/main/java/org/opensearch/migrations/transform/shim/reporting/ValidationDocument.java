package org.opensearch.migrations.transform.shim.reporting;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationDocument(
    // Identity and timing
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("request_id") String requestId,

    // Request context
    @JsonProperty("original_request") RequestRecord originalRequest,
    @JsonProperty("transformed_request") RequestRecord transformedRequest,
    @JsonProperty("collection_name") String collectionName,
    @JsonProperty("normalized_endpoint") String normalizedEndpoint,

    // Fixed metrics (universal — always present)
    @JsonProperty("baseline_hit_count") Long baselineHitCount,
    @JsonProperty("candidate_hit_count") Long candidateHitCount,
    @JsonProperty("hit_count_drift_percentage") Double hitCountDriftPercentage,
    @JsonProperty("baseline_response_time_ms") Long baselineResponseTimeMs,
    @JsonProperty("candidate_response_time_ms") Long candidateResponseTimeMs,
    @JsonProperty("response_time_delta_ms") Long responseTimeDeltaMs,

    // Comparisons (variable — only present when applicable)
    @JsonProperty("comparisons") List<ComparisonEntry> comparisons,

    // Custom transform metrics (transform-emitted)
    @JsonProperty("custom_metrics") Map<String, Object> customMetrics
) {
    /** Generic HTTP request record — used for both original and transformed requests. */
    public record RequestRecord(
        @JsonProperty("method") String method,
        @JsonProperty("uri") String uri,
        @JsonProperty("headers") Map<String, Object> headers,
        @JsonProperty("body") String body              // null unless include_request_body is true
    ) {}

    /** A single query-specific comparison result (e.g., facet bucket diff). */
    public record ComparisonEntry(
        @JsonProperty("type") String type,             // e.g., "facet_field", "json_facet"
        @JsonProperty("name") String name,             // e.g., the facet field name
        @JsonProperty("keys_match") Boolean keysMatch,
        @JsonProperty("missing_keys") List<String> missingKeys,
        @JsonProperty("extra_keys") List<String> extraKeys,
        @JsonProperty("value_drifts") List<ValueDrift> valueDrifts
    ) {}

    /** Per-key value drift within a comparison. */
    public record ValueDrift(
        @JsonProperty("key") String key,
        @JsonProperty("baseline_value") Number baselineValue,
        @JsonProperty("candidate_value") Number candidateValue,
        @JsonProperty("drift_percentage") Double driftPercentage
    ) {}
}
