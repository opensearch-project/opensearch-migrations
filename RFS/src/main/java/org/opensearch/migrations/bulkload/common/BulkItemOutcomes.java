package org.opensearch.migrations.bulkload.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates the terminal outcome of every item in one bulk request across all of its retry attempts.
 *
 * <p>Each item is counted exactly once, when it leaves the retry loop: as succeeded (acknowledged or
 * allowlisted) or as failed (non-retryable error type). Items still pending when retries are exhausted
 * are not counted — the batch errors and is re-driven by a later lease.
 */
public class BulkItemOutcomes {
    static final String UNKNOWN_FAILURE_TYPE = "unknown";

    private final AtomicLong succeeded = new AtomicLong();
    private final Map<String, AtomicLong> failedByType = new ConcurrentHashMap<>();

    public void addSucceeded(long count) {
        succeeded.addAndGet(count);
    }

    public void addFailed(String failureType) {
        failedByType.computeIfAbsent(failureType == null ? UNKNOWN_FAILURE_TYPE : failureType, k -> new AtomicLong())
            .incrementAndGet();
    }

    public long getSucceeded() {
        return succeeded.get();
    }

    public long getFailed() {
        return failedByType.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public Map<String, Long> getFailedByType() {
        var snapshot = new java.util.HashMap<String, Long>();
        failedByType.forEach((type, count) -> snapshot.put(type, count.get()));
        return snapshot;
    }
}
