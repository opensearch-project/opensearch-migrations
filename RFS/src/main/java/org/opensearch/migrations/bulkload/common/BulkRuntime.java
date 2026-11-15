package org.opensearch.migrations.bulkload.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowStats;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;

final class BulkRuntime {
    final AtomicLong oversizedOperations = new AtomicLong();
    final AtomicLong oversizedOperationsAtLastWarning = new AtomicLong();
    final AtomicLong lastOversizedWarningNanos = new AtomicLong();
    volatile long maxDocumentSizeBytes;
    volatile BulkRetryPolicy bulkRetryPolicy = new BulkRetryPolicy(new RetryBudget());
    final AtomicLong nextBatchId = new AtomicLong();
    final AtomicLong lastBulkRequestStartNanos = new AtomicLong();
    volatile AdaptiveLimit bulkInFlightLimit;
    final BulkFlowStats bulkFlowStats = new BulkFlowStats();
    volatile BulkFlowMetrics bulkFlowMetrics = bulkFlowStats;
    volatile Duration bulkResponseTimeout;

    void installBulkFlowMetrics() {
        var metrics = bulkFlowMetrics;
        bulkRetryPolicy.setMetrics(metrics);
        bulkRetryPolicy.budget().setMetrics(metrics);
        var limit = bulkInFlightLimit;
        if (limit != null) {
            limit.setMetrics(metrics);
        }
    }
}
