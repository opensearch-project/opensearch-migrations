package org.opensearch.migrations.bulkload.common.retry;

/**
 * Events from bulk flow control (retries, the retry budget, the in-flight limit) and from each bulk attempt, so
 * the strategy can be judged from metrics: is the backoff right, is the timeout right, is the budget sized right,
 * does slow start cost anything, do workers cut in lockstep, how much do retries waste. Implementations must be
 * cheap and thread-safe; every method defaults to doing nothing.
 */
public interface BulkFlowMetrics {
    BulkFlowMetrics NONE = new BulkFlowMetrics() {};

    /** How one bulk HTTP attempt ended. */
    enum AttemptOutcome {
        OK, ITEM_ERRORS, THROTTLED_429, ADMISSION_REJECTED, PAYLOAD_413, OTHER_4XX, SERVER_5XX, OTHER_STATUS,
        TIMEOUT, CONNECTION_RESET, CONNECT_FAILED;

        public String label() {
            return this == SERVER_5XX ? "5xx" : this == OTHER_4XX ? "other_4xx" : name().toLowerCase();
        }
    }

    /** Why documents were given up after retrying (counted per document; see {@code OpenSearchClient}). */
    enum GiveUpReason {
        /** A single document's request failed with an unknown status 4 counted times in a row. */
        UNKNOWN_FAILURES,
        /** A single document's request got no response 6 counted times in a row. */
        NO_RESPONSE,
        /** A document's item failed with a retryable, non-throttle error 4 times. */
        ITEM_FAILURES;

        public String label() {
            return name().toLowerCase();
        }
    }

    /** Why a batch was split in half. */
    enum SplitReason {
        STATUS, NO_RESPONSE, TOO_LARGE;

        public String label() {
            return name().toLowerCase();
        }
    }

    default void onAttempt(AttemptOutcome outcome, boolean retry, long durationMillis) {}

    /**
     * The body of one bulk attempt: its operations, its NDJSON bytes, and the bytes sent, which are fewer when the
     * body is gzipped and the same otherwise.
     */
    default void onRequestBody(int documents, long bytes, long sentBytes) {}

    /** A batch (one request chain, including its retries) finished after this many attempts. */
    default void onBatchFinished(long attempts) {}

    default void onDocumentsResent(long documents) {}

    /** {@code count} items in one response failed with {@code errorType} (already bounded to known types). */
    default void onItemErrors(String errorType, boolean retryable, long count) {}

    default void onRetry(BulkErrorClass errorClass, long delayMillis, boolean retryAfterHonored) {}

    /** {@code documents} were given up, and recorded in the failed document stream, for {@code reason}. */
    default void onGaveUp(GiveUpReason reason, long documents) {}

    default void onBatchSplit(SplitReason reason) {}

    /** A top-level batch started its first attempt; {@code id} is unique per batch. */
    default void onBatchOpened(long id) {}

    /**
     * The batch finished, {@code ageMillis} after it opened; {@code longestThrottleStreak} is the most consecutive
     * throttled attempts it saw.
     */
    default void onBatchClosed(long id, long ageMillis, int longestThrottleStreak) {}

    /**
     * Batch {@code id}'s last attempt left {@code documents} waiting because the target was throttling or couldn't
     * take writes (0: not waiting on the target). Cleared when the batch closes.
     */
    default void onWaitingOnTarget(long id, long documents) {}

    /** {@code documents} couldn't be written because the target was unavailable, for {@code reason}. */
    default void onTargetUnavailable(String reason, long documents) {}

    /** The target did or didn't answer a probe asked because there was no other evidence. */
    default void onTargetProbe(boolean answered) {}

    /**
     * A retry of a batch was sent {@code millis} after the batch's previous attempt ended: the whole wait, whatever
     * held it (backoff, retry tokens, an in-flight slot).
     */
    default void onAttemptGap(long millis) {}

    default void onBudgetSpent(BulkErrorClass errorClass, int tokens) {}

    default void onBudgetRefunded(int tokens) {}

    /** The budget couldn't pay for a retry, which now waits (counted once per stretch without tokens). */
    default void onBudgetEmpty() {}

    default void onBudgetWait(long millis) {}

    default void onSlotWait(long millis) {}

    default void onLimitCut(AdaptiveLimit.Signal signal, double before, double after) {}

    default void onLimitQuietReset() {}

    /** A success didn't grow the limit because the limit wasn't the bottleneck. */
    default void onLimitGrowthSkipped() {}

    /** The limit reached its max for the first time, this long after it was created. */
    default void onLimitFirstFull(long millisSinceStart) {}

    /** Calls every listener in turn. */
    static BulkFlowMetrics both(BulkFlowMetrics first, BulkFlowMetrics second) {
        return new BulkFlowMetrics() {
            @Override public void onAttempt(AttemptOutcome o, boolean r, long d) { first.onAttempt(o, r, d); second.onAttempt(o, r, d); }
            @Override public void onRequestBody(int d, long b, long s) { first.onRequestBody(d, b, s); second.onRequestBody(d, b, s); }
            @Override public void onBatchFinished(long a) { first.onBatchFinished(a); second.onBatchFinished(a); }
            @Override public void onDocumentsResent(long d) { first.onDocumentsResent(d); second.onDocumentsResent(d); }
            @Override public void onItemErrors(String t, boolean r, long c) { first.onItemErrors(t, r, c); second.onItemErrors(t, r, c); }
            @Override public void onRetry(BulkErrorClass c, long d, boolean h) { first.onRetry(c, d, h); second.onRetry(c, d, h); }
            @Override public void onGaveUp(GiveUpReason r, long d) { first.onGaveUp(r, d); second.onGaveUp(r, d); }
            @Override public void onBatchSplit(SplitReason r) { first.onBatchSplit(r); second.onBatchSplit(r); }
            @Override public void onBatchOpened(long i) { first.onBatchOpened(i); second.onBatchOpened(i); }
            @Override public void onBatchClosed(long i, long a, int t) { first.onBatchClosed(i, a, t); second.onBatchClosed(i, a, t); }
            @Override public void onWaitingOnTarget(long i, long d) { first.onWaitingOnTarget(i, d); second.onWaitingOnTarget(i, d); }
            @Override public void onTargetUnavailable(String r, long d) { first.onTargetUnavailable(r, d); second.onTargetUnavailable(r, d); }
            @Override public void onTargetProbe(boolean a) { first.onTargetProbe(a); second.onTargetProbe(a); }
            @Override public void onAttemptGap(long m) { first.onAttemptGap(m); second.onAttemptGap(m); }
            @Override public void onBudgetSpent(BulkErrorClass c, int t) { first.onBudgetSpent(c, t); second.onBudgetSpent(c, t); }
            @Override public void onBudgetRefunded(int t) { first.onBudgetRefunded(t); second.onBudgetRefunded(t); }
            @Override public void onBudgetEmpty() { first.onBudgetEmpty(); second.onBudgetEmpty(); }
            @Override public void onBudgetWait(long m) { first.onBudgetWait(m); second.onBudgetWait(m); }
            @Override public void onSlotWait(long m) { first.onSlotWait(m); second.onSlotWait(m); }
            @Override public void onLimitCut(AdaptiveLimit.Signal s, double b, double a) { first.onLimitCut(s, b, a); second.onLimitCut(s, b, a); }
            @Override public void onLimitQuietReset() { first.onLimitQuietReset(); second.onLimitQuietReset(); }
            @Override public void onLimitGrowthSkipped() { first.onLimitGrowthSkipped(); second.onLimitGrowthSkipped(); }
            @Override public void onLimitFirstFull(long m) { first.onLimitFirstFull(m); second.onLimitFirstFull(m); }
        };
    }
}
