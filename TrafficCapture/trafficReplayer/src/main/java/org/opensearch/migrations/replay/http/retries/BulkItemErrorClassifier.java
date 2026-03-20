package org.opensearch.migrations.replay.http.retries;

import java.util.Set;

import lombok.experimental.UtilityClass;

/**
 * Classifies OpenSearch bulk item error types as retryable or non-retryable.
 * <p>
 * Non-retryable errors are client-side or logical errors that will never succeed on retry.
 * Any error type not in the non-retryable set is treated as retryable (fail-open).
 */
@UtilityClass
public class BulkItemErrorClassifier {

    /**
     * Error types that are definitively non-retryable — these are client/logic errors
     * that will produce the same failure on every attempt.
     */
    private static final Set<String> NON_RETRYABLE_ERROR_TYPES = Set.of(
        "version_conflict_engine_exception",
        "mapper_parsing_exception",
        "strict_dynamic_mapping_exception",
        "document_missing_exception",
        "action_request_validation_exception",
        "invalid_index_name_exception",
        "routing_missing_exception",
        "illegal_argument_exception",
        "resource_already_exists_exception"
    );

    /**
     * @return true if this error type will never succeed on retry
     */
    public static boolean isNonRetryable(String errorType) {
        return errorType != null && NON_RETRYABLE_ERROR_TYPES.contains(errorType);
    }
}
