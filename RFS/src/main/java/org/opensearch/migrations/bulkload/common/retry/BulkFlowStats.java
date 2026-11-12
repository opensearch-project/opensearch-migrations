package org.opensearch.migrations.bulkload.common.retry;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;

/**
 * Running totals of the flow-control events for one process, for the per-work-item metrics record (a process
 * runs one work item). Thread-safe.
 */
public class BulkFlowStats implements BulkFlowMetrics {
    private final AtomicLong throttleRetries = new AtomicLong();
    private final AtomicLong transientRetries = new AtomicLong();
    private final AtomicLong budgetWaitMillis = new AtomicLong();
    private final AtomicLong slotWaitMillis = new AtomicLong();
    private final AtomicLong limitCuts = new AtomicLong();
    private final DoubleAccumulator lowestLimit = new DoubleAccumulator(Math::min, Double.MAX_VALUE);
    private final DoubleAccumulator highestLimit = new DoubleAccumulator(Math::max, 0);

    /**
     * What the work-item record shows. {@code lowestLimit} and {@code highestLimit} are the limit right after and
     * right before each cut, and at the end; null with no in-flight limit.
     */
    @SuppressWarnings({"java:S100", "java:S1186"})
    public record Summary(long throttleRetries, long transientRetries, long budgetWaitMillis, long slotWaitMillis,
                          long limitCuts, Double lowestLimit, Double highestLimit, Double finalLimit) {}

    @Override
    public void onRetry(BulkErrorClass errorClass, long delayMillis, boolean retryAfterHonored) {
        (errorClass == BulkErrorClass.THROTTLE ? throttleRetries : transientRetries).incrementAndGet();
    }

    @Override
    public void onBudgetWait(long millis) {
        budgetWaitMillis.addAndGet(millis);
    }

    @Override
    public void onSlotWait(long millis) {
        slotWaitMillis.addAndGet(millis);
    }

    @Override
    public void onLimitCut(AdaptiveLimit.Signal signal, double before, double after) {
        limitCuts.incrementAndGet();
        highestLimit.accumulate(before);
        lowestLimit.accumulate(after);
    }

    public Summary snapshot(AdaptiveLimit limit) {
        Double current = limit == null ? null : limit.limit();
        Double lowest = null;
        Double highest = null;
        if (current != null) {
            lowest = Math.min(lowestLimit.get(), current);
            highest = Math.max(highestLimit.get(), current);
        }
        return new Summary(throttleRetries.get(), transientRetries.get(), budgetWaitMillis.get(),
            slotWaitMillis.get(), limitCuts.get(), lowest, highest, current);
    }
}
