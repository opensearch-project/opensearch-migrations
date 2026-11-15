package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.LongAdder;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Logs a line at INFO each {@link #INTERVAL} about bulk flow control in this process, so backpressure shows in the
 * logs as well as in metrics: what happened since the last line (attempts by how they ended, request sizes before
 * and after gzip, retries, cuts, splits, documents the target couldn't take, documents given up, documents re-sent)
 * and where the in-flight limit, the retry budget and the documents waiting on the target are now. Intervals with no
 * bulk activity log nothing. The line is a WARN while documents are waiting on the target (throttled or unavailable),
 * so a target that holds the migration up shows in the logs.
 */
@Slf4j
public class BulkFlowHeartbeat implements BulkFlowMetrics, Disposable {
    public static final Duration INTERVAL = Duration.ofMinutes(1);

    private final RetryBudget budget;
    private final AdaptiveLimit limit;
    private final Duration interval;
    private final Map<AttemptOutcome, LongAdder> attempts = new EnumMap<>(AttemptOutcome.class);
    private final LongAdder throttleRetries = new LongAdder();
    private final LongAdder unavailableRetries = new LongAdder();
    private final LongAdder transientRetries = new LongAdder();
    private final LongAdder unavailableDocuments = new LongAdder();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> waitingOnTarget =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final LongAdder gaveUp = new LongAdder();
    private final LongAdder splits = new LongAdder();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> openBatches =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final LongAdder limitCuts = new LongAdder();
    private final LongAdder quietResets = new LongAdder();
    private final LongAdder documentsResent = new LongAdder();
    private final LongAdder budgetEmpty = new LongAdder();
    private final LongAdder requestBodies = new LongAdder();
    private final LongAdder requestDocuments = new LongAdder();
    private final LongAdder requestBytes = new LongAdder();
    private final LongAdder requestSentBytes = new LongAdder();
    private final Disposable timer;

    /** @param limit null when there is no in-flight limit */
    public BulkFlowHeartbeat(RetryBudget budget, AdaptiveLimit limit) {
        this(budget, limit, INTERVAL, Schedulers.parallel());
    }

    BulkFlowHeartbeat(RetryBudget budget, AdaptiveLimit limit, Duration interval, Scheduler scheduler) {
        this.budget = budget;
        this.limit = limit;
        this.interval = interval;
        for (var outcome : AttemptOutcome.values()) {
            attempts.put(outcome, new LongAdder());
        }
        this.timer = Flux.interval(interval, interval, scheduler).subscribe(tick -> logInterval());
    }

    @Override
    public void onAttempt(AttemptOutcome outcome, boolean retry, long durationMillis) {
        attempts.get(outcome).increment();
    }

    @Override
    public void onRequestBody(int documents, long bytes, long sentBytes) {
        requestBodies.increment();
        requestDocuments.add(documents);
        requestBytes.add(bytes);
        requestSentBytes.add(sentBytes);
    }

    @Override
    public void onRetry(BulkErrorClass errorClass, long delayMillis, boolean retryAfterHonored) {
        (switch (errorClass) {
            case THROTTLE -> throttleRetries;
            case UNAVAILABLE -> unavailableRetries;
            default -> transientRetries;
        }).increment();
    }

    @Override
    public void onGaveUp(GiveUpReason reason, long documents) {
        gaveUp.add(documents);
    }

    @Override
    public void onBatchSplit(SplitReason reason) {
        splits.increment();
    }

    @Override
    public void onBatchOpened(long id) {
        openBatches.put(id, System.currentTimeMillis());
    }

    @Override
    public void onBatchClosed(long id, long ageMillis, int longestThrottleStreak) {
        openBatches.remove(id);
        waitingOnTarget.remove(id);
    }

    @Override
    public void onWaitingOnTarget(long id, long documents) {
        if (documents > 0) {
            waitingOnTarget.put(id, documents);
        } else {
            waitingOnTarget.remove(id);
        }
    }

    @Override
    public void onTargetUnavailable(String reason, long documents) {
        unavailableDocuments.add(documents);
    }

    @Override
    public void onLimitCut(AdaptiveLimit.Signal signal, double before, double after) {
        limitCuts.increment();
    }

    @Override
    public void onLimitQuietReset() {
        quietResets.increment();
    }

    @Override
    public void onDocumentsResent(long documents) {
        documentsResent.add(documents);
    }

    @Override
    public void onBudgetEmpty() {
        budgetEmpty.increment();
    }

    void logInterval() {
        var byOutcome = new StringJoiner(", ");
        long total = 0;
        for (var entry : attempts.entrySet()) {
            long count = entry.getValue().sumThenReset();
            if (count > 0) {
                byOutcome.add(entry.getKey().label() + " " + count);
                total += count;
            }
        }
        long throttle = throttleRetries.sumThenReset();
        long unavailable = unavailableRetries.sumThenReset();
        long transientCount = transientRetries.sumThenReset();
        long unavailableDocs = unavailableDocuments.sumThenReset();
        long waitingOnTargetDocs = waitingOnTarget.values().stream().mapToLong(Long::longValue).sum();
        long cuts = limitCuts.sumThenReset();
        long resets = quietResets.sumThenReset();
        long gaveUpCount = gaveUp.sumThenReset();
        long splitCount = splits.sumThenReset();
        long oldestOpenMillis = openBatches.values().stream().mapToLong(Long::longValue).min()
            .stream().map(opened -> System.currentTimeMillis() - opened).findFirst().orElse(0L);
        long resent = documentsResent.sumThenReset();
        long empty = budgetEmpty.sumThenReset();
        long bodies = requestBodies.sumThenReset();
        long documents = requestDocuments.sumThenReset();
        long bytes = requestBytes.sumThenReset();
        long sentBytes = requestSentBytes.sumThenReset();
        int inFlight = limit == null ? 0 : limit.inFlight();
        int waiting = limit == null ? 0 : limit.waiters();
        if (total == 0 && inFlight == 0 && waiting == 0) {
            return;
        }
        var now = limit == null
            ? "no in-flight limit"
            : String.format("limit %.2f of %d (threshold %.2f), %d in flight, %d waiting for a slot",
                limit.limit(), limit.max(), limit.threshold(), inFlight, waiting);
        (waitingOnTargetDocs > 0 ? log.atWarn() : log.atInfo())
            .setMessage("Bulk flow in the last {}: {} attempts ({}); {}; {} retries ({} throttled, {} unavailable, "
                + "{} transient); {} documents the target couldn't take; "
                + "{} limit cuts, {} quiet resets; {} batches split; {} documents given up; {} documents re-sent; "
                + "retry budget empty {} times. Now: {}; retry budget {} of {} tokens, {} retries waiting; "
                + "{} documents waiting on the target; oldest open batch {} ms old")
            .addArgument(interval)
            .addArgument(total)
            .addArgument(total == 0 ? "none finished" : byOutcome.toString())
            .addArgument(() -> describeSizes(bodies, documents, bytes, sentBytes))
            .addArgument(throttle + unavailable + transientCount)
            .addArgument(throttle)
            .addArgument(unavailable)
            .addArgument(transientCount)
            .addArgument(unavailableDocs)
            .addArgument(cuts)
            .addArgument(resets)
            .addArgument(splitCount)
            .addArgument(gaveUpCount)
            .addArgument(resent)
            .addArgument(empty)
            .addArgument(now)
            .addArgument(budget::tokens)
            .addArgument(budget::capacity)
            .addArgument(budget::waiters)
            .addArgument(waitingOnTargetDocs)
            .addArgument(oldestOpenMillis)
            .log();
    }

    private static String describeSizes(long bodies, long documents, long bytes, long sentBytes) {
        if (bodies == 0) {
            return "no requests sent";
        }
        return String.format("%d requests sent, averaging %d documents and %d bytes (%d bytes on the wire)",
            bodies, documents / bodies, bytes / bodies, sentBytes / bodies);
    }

    @Override
    public void dispose() {
        timer.dispose();
    }

    @Override
    public boolean isDisposed() {
        return timer.isDisposed();
    }
}
