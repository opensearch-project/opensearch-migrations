package org.opensearch.migrations.replay.http.retries;

import java.util.Collection;
import java.util.Set;

import org.opensearch.migrations.BulkDocErrorTypes;

import lombok.Getter;

/**
 * Classifies OpenSearch bulk item error types as retryable or non-retryable.
 * <p>
 * Non-retryable errors are client-side or logical errors that will never succeed on retry.
 * An error whose type and causes are all outside the non-retryable set is treated as retryable (fail-open).
 */
public class BulkItemErrorClassifier {

    @Getter
    private final Set<String> nonRetryableErrorTypes;

    public BulkItemErrorClassifier() {
        this(BulkDocErrorTypes.NON_RETRYABLE);
    }

    public BulkItemErrorClassifier(Set<String> nonRetryableErrorTypes) {
        this.nonRetryableErrorTypes = Set.copyOf(nonRetryableErrorTypes);
    }

    /**
     * @return true if this error type will never succeed on retry
     */
    public boolean isNonRetryable(String errorType) {
        return errorType != null && nonRetryableErrorTypes.contains(errorType);
    }

    /**
     * @param errorTypeChain every type in one item's error: its own, then its {@code caused_by} types
     * @return true if any of them will never succeed on retry; see {@link BulkDocErrorTypes#anyNonRetryable}
     */
    public boolean anyNonRetryable(Collection<String> errorTypeChain) {
        return BulkDocErrorTypes.anyNonRetryable(errorTypeChain, nonRetryableErrorTypes);
    }
}
