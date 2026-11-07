package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.parsing.BulkResponseParser;

/**
 * What kind of failure a failed bulk attempt is, judged from the error it ended with, first match wins:
 * <ol>
 *   <li>{@link #UNAVAILABLE} if there is a response that isn't 2xx and reports a {@code cluster_block_exception}
 *       (a read-only index or cluster is 403, the disk flood stage 429).</li>
 *   <li>{@link #THROTTLE} if there is a response whose status is 429, or 200 or 201 with at least one item of kind
 *       {@link BulkItemErrorKind#THROTTLE}.</li>
 *   <li>{@link #UNAVAILABLE} if there is a response whose status is 503, or 200 or 201 whose failed items are all of
 *       kind {@link BulkItemErrorKind#UNAVAILABLE}; or no response because the connection couldn't be made or was
 *       reset or closed.</li>
 *   <li>{@link #AUTH} if there is a response whose status is 401 or 403.</li>
 *   <li>{@link #TRANSIENT} otherwise: no response within the response timeout, any other status, or a 2xx with an
 *       item of kind {@link BulkItemErrorKind#UNKNOWN}.</li>
 * </ol>
 * 400 and 413, which describe the request itself, and items of kind {@link BulkItemErrorKind#DOCUMENT} are handled
 * before this, by splitting the batch or recording the document in the failed document stream. Each class's
 * {@link #cost()} is what a retry takes from the {@link RetryBudget}. Throttles and unavailability are about the
 * target and never count against a document. Transient failures advance the client's fixed split or document
 * give-up thresholds; see {@code OpenSearchClient}.
 */
public enum BulkErrorClass {
    /** The target pushed back: retried, at a cost of 5 tokens, and never counted against a document. */
    THROTTLE(5),
    /** The target can't take writes right now: retried, at a cost of 5 tokens, and never counted. */
    UNAVAILABLE(5),
    /** Possibly the document's fault: retried, at a cost of 10 tokens. */
    TRANSIENT(10),
    /** The target refused the credentials: not retried; the worker stops without failing any document. */
    AUTH(0);

    static final String CLUSTER_BLOCK = "cluster_block_exception";

    private final int cost;

    BulkErrorClass(int cost) {
        this.cost = cost;
    }

    /** {@link RetryBudget} tokens a retry of this class spends. */
    public int cost() {
        return cost;
    }

    public boolean retryable() {
        return this != AUTH;
    }

    /** Whether the target, not a document, is the reason: never counted against a document. */
    public boolean isTargetState() {
        return this == THROTTLE || this == UNAVAILABLE;
    }

    public static BulkErrorClass of(Throwable failure) {
        for (var cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof OpenSearchClient.NoResponseException noResponse) {
                return noResponse.isResponseTimeout() ? TRANSIENT : UNAVAILABLE;
            }
            if (cursor instanceof OpenSearchClient.OperationFailed operationFailed && operationFailed.response != null) {
                return of(operationFailed.response);
            }
        }
        return TRANSIENT;
    }

    public static BulkErrorClass of(HttpResponse response) {
        if (isClusterBlock(response)) {
            return UNAVAILABLE;
        }
        if (isThrottle(response)) {
            return THROTTLE;
        }
        int status = response.statusCode;
        if (status == 503) {
            return UNAVAILABLE;
        }
        if (status == 401 || status == 403) {
            return AUTH;
        }
        if ((status == 200 || status == 201) && response.body != null) {
            var partition = BulkResponseParser.partitionItems(response.body, DocumentExceptionAllowlist.empty());
            if (partition != null && !partition.getRetryableFailures().isEmpty() && partition.getRetryableFailures()
                .stream().allMatch(f -> f.getKind() == BulkItemErrorKind.UNAVAILABLE)) {
                return UNAVAILABLE;
            }
        }
        return TRANSIENT;
    }

    /** A whole request refused because writes are blocked: not a throttle, and not the credentials. */
    public static boolean isClusterBlock(HttpResponse response) {
        return !(response.statusCode >= 200 && response.statusCode < 300) && response.body != null
            && response.body.contains(CLUSTER_BLOCK);
    }

    /** The target pushed back: a 429 that isn't a write block, or a 200/201 with items of kind THROTTLE. */
    public static boolean isThrottle(HttpResponse response) {
        int status = response.statusCode;
        if (status == 429) {
            return !isClusterBlock(response);
        }
        // The substring checks keep successful responses from being parsed a second time.
        return (status == 200 || status == 201) && response.body != null
            && (response.body.contains("rejected_execution_exception")
                || response.body.contains("circuit_breaking_exception"))
            && hasRejectedItems(response);
    }

    /** The {@code Retry-After} the target sent with this failure, if it sent one: seconds or an HTTP date. */
    public static Optional<Duration> retryAfter(Throwable failure, Instant now) {
        return responseOf(failure).map(r -> r.headers).flatMap(BulkErrorClass::header).flatMap(value -> {
            try {
                return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))));
            } catch (NumberFormatException notSeconds) {
                try {
                    var at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Optional.of(at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO);
                } catch (DateTimeParseException notADate) {
                    return Optional.empty();
                }
            }
        });
    }

    private static Optional<String> header(Map<String, String> headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
            .filter(e -> "retry-after".equalsIgnoreCase(e.getKey()) && e.getValue() != null)
            .map(Map.Entry::getValue)
            .findFirst();
    }

    static Optional<HttpResponse> responseOf(Throwable failure) {
        for (var cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof OpenSearchClient.NoResponseException) {
                return Optional.empty();
            }
            if (cursor instanceof OpenSearchClient.OperationFailed operationFailed && operationFailed.response != null) {
                return Optional.of(operationFailed.response);
            }
        }
        return Optional.empty();
    }

    private static boolean hasRejectedItems(HttpResponse response) {
        if (response.body == null) {
            return false;
        }
        var partition = BulkResponseParser.partitionItems(response.body, DocumentExceptionAllowlist.empty());
        return partition != null && partition.getRetryableFailures().stream()
            .anyMatch(f -> f.getKind() == BulkItemErrorKind.THROTTLE);
    }
}
