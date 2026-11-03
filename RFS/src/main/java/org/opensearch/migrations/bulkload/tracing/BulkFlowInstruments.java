package org.opensearch.migrations.bulkload.tracing;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkErrorClass;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics;
import org.opensearch.migrations.bulkload.common.retry.BulkItemErrorKind;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableLongGauge;

/**
 * OpenTelemetry instruments for bulk flow control: one set per process and target, attributes limited to small
 * fixed sets (outcome, error class, first attempt or retry, reason, a bounded item error type). Index, shard and
 * worker ids are deliberately absent; the pod is already a resource attribute.
 */
public class BulkFlowInstruments implements BulkFlowMetrics {
    public static final String BULK_ATTEMPTS = "bulkAttempts";
    public static final String BULK_ATTEMPT_DURATION = "bulkAttemptDuration";
    public static final String BULK_RETRIES = "bulkRetries";
    public static final String BULK_RETRY_DELAY = "bulkRetryDelay";
    public static final String BULK_RETRY_AFTER_HONORED = "bulkRetryAfterHonored";
    public static final String BULK_DOCS_GAVE_UP = "bulkDocsGaveUp";
    public static final String BULK_BATCH_SPLITS = "bulkBatchSplits";
    public static final String BULK_BATCH_AGE = "bulkBatchAge";
    public static final String BULK_OLDEST_OPEN_BATCH_AGE = "bulkOldestOpenBatchAge";
    public static final String BULK_THROTTLE_STREAK = "bulkThrottleStreak";
    public static final String BULK_DOCS_WAITING_ON_TARGET = "bulkDocsWaitingOnTarget";
    public static final String BULK_DOCS_TARGET_UNAVAILABLE = "bulkDocsTargetUnavailable";
    public static final String BULK_TARGET_PROBES = "bulkTargetProbes";
    public static final String BULK_ATTEMPT_GAP = "bulkAttemptGap";
    public static final String BULK_ATTEMPTS_PER_BATCH = "bulkAttemptsPerBatch";
    public static final String BULK_ITEM_ERRORS = "bulkItemErrors";
    public static final String BULK_DOCS_RESENT = "bulkDocsResent";
    public static final String RETRY_BUDGET_TOKENS = "retryBudgetTokens";
    public static final String RETRY_BUDGET_SPENT = "retryBudgetSpent";
    public static final String RETRY_BUDGET_REFUNDED = "retryBudgetRefunded";
    public static final String RETRY_BUDGET_EMPTY = "retryBudgetEmpty";
    public static final String RETRY_BUDGET_WAIT = "retryBudgetWait";
    public static final String BULK_LIMIT = "bulkLimit";
    public static final String BULK_LIMIT_MAX = "bulkLimitMax";
    public static final String BULK_WORKERS_BY_LIMIT_UTILIZATION = "bulkWorkersByLimitUtilization";
    public static final String BULK_LIMIT_THRESHOLD = "bulkLimitThreshold";
    public static final String BULK_IN_FLIGHT = "bulkInFlight";
    public static final String BULK_SLOT_WAITERS = "bulkSlotWaiters";
    public static final String BULK_SLOT_WAIT = "bulkSlotWait";
    public static final String BULK_LIMIT_CUTS = "bulkLimitCuts";
    public static final String BULK_LIMIT_QUIET_RESETS = "bulkLimitQuietResets";
    public static final String BULK_LIMIT_GROWTH_SKIPPED = "bulkLimitGrowthSkipped";
    public static final String BULK_TIME_TO_FULL_LIMIT = "bulkTimeToFullLimit";
    public static final String BULK_REQUEST_DOCUMENTS = "bulkRequestDocuments";
    public static final String BULK_REQUEST_BYTES = "bulkRequestBytes";
    public static final String BULK_REQUEST_SENT_BYTES = "bulkRequestSentBytes";

    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    public static final AttributeKey<String> ATTEMPT = AttributeKey.stringKey("attempt");
    public static final AttributeKey<String> ERROR_CLASS = AttributeKey.stringKey("errorClass");
    public static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("errorType");
    public static final AttributeKey<Boolean> RETRYABLE = AttributeKey.booleanKey("retryable");
    public static final AttributeKey<String> SIGNAL = AttributeKey.stringKey("signal");
    public static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    public static final AttributeKey<String> RANGE = AttributeKey.stringKey("range");

    /** Reasons reported for unavailable documents as themselves; any other is reported as {@code other}. */
    static final java.util.Set<String> TARGET_UNAVAILABLE_REASONS = Stream.concat(
            BulkItemErrorKind.UNAVAILABLE_TYPES.stream(),
            Stream.of("http_503", "connect_failed", "connection_reset"))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** The long-duration bounds plus the response-timeout choices, to see how close attempts come to the bound. */
    static final List<Double> ATTEMPT_DURATION_BUCKETS_MS = Stream.concat(
            CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS.stream(),
            Stream.of(65_000.0, 90_000.0, 180_000.0, 240_000.0, 310_000.0))
        .distinct().sorted().toList();
    static final List<Long> ATTEMPTS_PER_BATCH_BUCKETS = List.of(1L, 2L, 3L, 4L, 5L, 6L, 8L, 12L, 16L);
    static final List<Long> THROTTLE_STREAK_BUCKETS = List.of(0L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L);
    static final List<Long> REQUEST_DOCUMENTS_BUCKETS =
        List.of(1L, 2L, 5L, 10L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 50_000L, 100_000L);
    static final List<String> LIMIT_UTILIZATION_RANGES =
        List.of("0-24%", "25-49%", "50-74%", "75-99%", "100%");
    /** Powers of four from 1 KiB to 256 MiB. */
    static final List<Long> REQUEST_BYTES_BUCKETS = Stream.iterate(1024L, b -> b <= 256L * 1024 * 1024, b -> b * 4)
        .toList();

    private static final String COUNT = "count";
    private static final String MS = "ms";
    private static final String BYTES = "bytes";

    private final LongCounter attempts;
    private final DoubleHistogram attemptDuration;
    private final LongCounter retries;
    private final DoubleHistogram retryDelay;
    private final LongCounter retryAfterHonored;
    private final LongCounter docsGaveUp;
    private final LongCounter batchSplits;
    private final DoubleHistogram batchAge;
    private final LongHistogram throttleStreak;
    private final LongCounter docsTargetUnavailable;
    private final LongCounter targetProbes;
    private final DoubleHistogram attemptGap;
    // Documents each open batch has waiting on the target, for the gauge.
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> waitingOnTarget =
        new java.util.concurrent.ConcurrentHashMap<>();
    // Open top-level batches by id, with when they opened, for the oldest-open-batch gauge.
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> openBatches =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final LongHistogram attemptsPerBatch;
    private final LongCounter itemErrors;
    private final LongCounter docsResent;
    private final LongCounter budgetSpent;
    private final LongCounter budgetRefunded;
    private final LongCounter budgetEmpty;
    private final DoubleHistogram budgetWait;
    private final DoubleHistogram slotWait;
    private final LongCounter limitCuts;
    private final LongCounter limitQuietResets;
    private final LongCounter limitGrowthSkipped;
    private final DoubleHistogram timeToFullLimit;
    private final LongHistogram requestDocuments;
    private final LongHistogram requestBytes;
    private final LongHistogram requestSentBytes;
    // The SDK holds the callbacks; the references keep them reachable alongside this object.
    @SuppressWarnings("unused")
    private final List<AutoCloseable> gauges;

    public BulkFlowInstruments(Meter meter, RetryBudget budget, AdaptiveLimit limit) {
        attempts = meter.counterBuilder(BULK_ATTEMPTS).setUnit(COUNT).build();
        attemptDuration = meter.histogramBuilder(BULK_ATTEMPT_DURATION).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(ATTEMPT_DURATION_BUCKETS_MS).build();
        retries = meter.counterBuilder(BULK_RETRIES).setUnit(COUNT).build();
        retryDelay = meter.histogramBuilder(BULK_RETRY_DELAY).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.DURATION_BUCKETS_MS).build();
        retryAfterHonored = meter.counterBuilder(BULK_RETRY_AFTER_HONORED).setUnit(COUNT).build();
        docsGaveUp = meter.counterBuilder(BULK_DOCS_GAVE_UP).setUnit(COUNT).build();
        batchSplits = meter.counterBuilder(BULK_BATCH_SPLITS).setUnit(COUNT).build();
        batchAge = meter.histogramBuilder(BULK_BATCH_AGE).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS).build();
        throttleStreak = meter.histogramBuilder(BULK_THROTTLE_STREAK).setUnit(COUNT).ofLongs()
            .setExplicitBucketBoundariesAdvice(THROTTLE_STREAK_BUCKETS).build();
        docsTargetUnavailable = meter.counterBuilder(BULK_DOCS_TARGET_UNAVAILABLE).setUnit(COUNT).build();
        targetProbes = meter.counterBuilder(BULK_TARGET_PROBES).setUnit(COUNT).build();
        // Rare events start at 0, so their first occurrence counts in rate() and a run without them shows 0, not
        // no data.
        for (var reason : GiveUpReason.values()) {
            docsGaveUp.add(0, Attributes.of(REASON, reason.label()));
        }
        targetProbes.add(0, Attributes.of(RESULT, "answered"));
        targetProbes.add(0, Attributes.of(RESULT, "unanswered"));
        docsTargetUnavailable.add(0, Attributes.of(REASON, "other"));
        for (var reason : SplitReason.values()) {
            batchSplits.add(0, Attributes.of(REASON, reason.label()));
        }
        attemptGap = meter.histogramBuilder(BULK_ATTEMPT_GAP).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS).build();
        attemptsPerBatch = meter.histogramBuilder(BULK_ATTEMPTS_PER_BATCH).setUnit(COUNT).ofLongs()
            .setExplicitBucketBoundariesAdvice(ATTEMPTS_PER_BATCH_BUCKETS).build();
        itemErrors = meter.counterBuilder(BULK_ITEM_ERRORS).setUnit(COUNT).build();
        docsResent = meter.counterBuilder(BULK_DOCS_RESENT).setUnit(COUNT).build();
        budgetSpent = meter.counterBuilder(RETRY_BUDGET_SPENT).setUnit(COUNT).build();
        budgetRefunded = meter.counterBuilder(RETRY_BUDGET_REFUNDED).setUnit(COUNT).build();
        budgetEmpty = meter.counterBuilder(RETRY_BUDGET_EMPTY).setUnit(COUNT).build();
        budgetWait = meter.histogramBuilder(RETRY_BUDGET_WAIT).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS).build();
        slotWait = meter.histogramBuilder(BULK_SLOT_WAIT).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS).build();
        limitCuts = meter.counterBuilder(BULK_LIMIT_CUTS).setUnit(COUNT).build();
        for (var signal : AdaptiveLimit.Signal.values()) {
            if (signal.isThrottle()) {
                limitCuts.add(0, Attributes.of(SIGNAL, lower(signal)));
            }
        }
        limitQuietResets = meter.counterBuilder(BULK_LIMIT_QUIET_RESETS).setUnit(COUNT).build();
        limitQuietResets.add(0);
        limitGrowthSkipped = meter.counterBuilder(BULK_LIMIT_GROWTH_SKIPPED).setUnit(COUNT).build();
        timeToFullLimit = meter.histogramBuilder(BULK_TIME_TO_FULL_LIMIT).setUnit(MS)
            .setExplicitBucketBoundariesAdvice(CommonScopedMetricInstruments.LONG_DURATION_BUCKETS_MS).build();
        requestDocuments = meter.histogramBuilder(BULK_REQUEST_DOCUMENTS).setUnit(COUNT).ofLongs()
            .setExplicitBucketBoundariesAdvice(REQUEST_DOCUMENTS_BUCKETS).build();
        requestBytes = meter.histogramBuilder(BULK_REQUEST_BYTES).setUnit(BYTES).ofLongs()
            .setExplicitBucketBoundariesAdvice(REQUEST_BYTES_BUCKETS).build();
        requestSentBytes = meter.histogramBuilder(BULK_REQUEST_SENT_BYTES).setUnit(BYTES).ofLongs()
            .setExplicitBucketBoundariesAdvice(REQUEST_BYTES_BUCKETS).build();

        var registered = new ArrayList<AutoCloseable>();
        registered.add(meter.gaugeBuilder(BULK_OLDEST_OPEN_BATCH_AGE).setUnit(MS).ofLongs()
            .buildWithCallback(m -> m.record(openBatches.values().stream().mapToLong(Long::longValue).min()
                .stream().map(opened -> System.currentTimeMillis() - opened).findFirst().orElse(0L))));
        registered.add(meter.gaugeBuilder(BULK_DOCS_WAITING_ON_TARGET).setUnit(COUNT).ofLongs()
            .buildWithCallback(m -> m.record(waitingOnTarget.values().stream().mapToLong(Long::longValue).sum())));
        if (budget != null) {
            ObservableLongGauge tokens = meter.gaugeBuilder(RETRY_BUDGET_TOKENS).setUnit(COUNT).ofLongs()
                .buildWithCallback(m -> m.record(budget.tokens()));
            registered.add(tokens);
        }
        if (limit != null) {
            ObservableDoubleGauge current = meter.gaugeBuilder(BULK_LIMIT).setUnit(COUNT)
                .buildWithCallback(m -> m.record(limit.limit()));
            ObservableDoubleGauge maximum = meter.gaugeBuilder(BULK_LIMIT_MAX).setUnit(COUNT)
                .buildWithCallback(m -> m.record(limit.max()));
            ObservableLongGauge utilization = meter.gaugeBuilder(BULK_WORKERS_BY_LIMIT_UTILIZATION).setUnit(COUNT)
                .ofLongs()
                .buildWithCallback(m -> {
                    var activeRange = limitUtilizationRange(limit);
                    LIMIT_UTILIZATION_RANGES.forEach(range ->
                        m.record(range.equals(activeRange) ? 1 : 0, Attributes.of(RANGE, range)));
                });
            ObservableDoubleGauge threshold = meter.gaugeBuilder(BULK_LIMIT_THRESHOLD).setUnit(COUNT)
                .buildWithCallback(m -> m.record(limit.threshold()));
            ObservableLongGauge inFlight = meter.gaugeBuilder(BULK_IN_FLIGHT).setUnit(COUNT).ofLongs()
                .buildWithCallback(m -> m.record(limit.inFlight()));
            ObservableLongGauge waiters = meter.gaugeBuilder(BULK_SLOT_WAITERS).setUnit(COUNT).ofLongs()
                .buildWithCallback(m -> m.record(limit.waiters()));
            registered.addAll(List.of(current, maximum, utilization, threshold, inFlight, waiters));
        }
        gauges = List.copyOf(registered);
    }

    private static String limitUtilizationRange(AdaptiveLimit limit) {
        double percent = 100 * limit.limit() / limit.max();
        if (percent >= 100) {
            return "100%";
        }
        if (percent >= 75) {
            return "75-99%";
        }
        if (percent >= 50) {
            return "50-74%";
        }
        if (percent >= 25) {
            return "25-49%";
        }
        return "0-24%";
    }

    private static String attemptLabel(boolean retry) {
        return retry ? "retry" : "first";
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase();
    }

    @Override
    public void onAttempt(AttemptOutcome outcome, boolean retry, long durationMillis) {
        attempts.add(1, Attributes.of(OUTCOME, outcome.label(), ATTEMPT, attemptLabel(retry)));
        attemptDuration.record(durationMillis, Attributes.of(OUTCOME, outcome.label()));
    }

    @Override
    public void onRequestBody(int documents, long bytes, long sentBytes) {
        requestDocuments.record(documents);
        requestBytes.record(bytes);
        requestSentBytes.record(sentBytes);
    }

    @Override
    public void onBatchFinished(long attemptCount) {
        attemptsPerBatch.record(attemptCount);
    }

    @Override
    public void onDocumentsResent(long documents) {
        docsResent.add(documents);
    }

    @Override
    public void onItemErrors(String errorType, boolean retryable, long count) {
        itemErrors.add(count, Attributes.of(ERROR_TYPE, errorType, RETRYABLE, retryable));
    }

    @Override
    public void onRetry(BulkErrorClass errorClass, long delayMillis, boolean honoredRetryAfter) {
        var attributes = Attributes.of(ERROR_CLASS, lower(errorClass));
        retries.add(1, attributes);
        retryDelay.record(delayMillis, attributes);
        if (honoredRetryAfter) {
            retryAfterHonored.add(1);
        }
    }

    @Override
    public void onGaveUp(GiveUpReason reason, long documents) {
        docsGaveUp.add(documents, Attributes.of(REASON, reason.label()));
    }

    @Override
    public void onBatchSplit(SplitReason reason) {
        batchSplits.add(1, Attributes.of(REASON, reason.label()));
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
        docsTargetUnavailable.add(documents,
            Attributes.of(REASON, TARGET_UNAVAILABLE_REASONS.contains(reason) ? reason : "other"));
    }

    @Override
    public void onAttemptGap(long millis) {
        attemptGap.record(millis);
    }

    @Override
    public void onTargetProbe(boolean answered) {
        targetProbes.add(1, Attributes.of(RESULT, answered ? "answered" : "unanswered"));
    }

    @Override
    public void onBatchOpened(long id) {
        openBatches.put(id, System.currentTimeMillis());
    }

    @Override
    public void onBatchClosed(long id, long ageMillis, int longestThrottleStreak) {
        openBatches.remove(id);
        waitingOnTarget.remove(id);
        batchAge.record(ageMillis);
        throttleStreak.record(longestThrottleStreak);
    }

    @Override
    public void onBudgetSpent(BulkErrorClass errorClass, int tokens) {
        budgetSpent.add(tokens, Attributes.of(ERROR_CLASS, lower(errorClass)));
    }

    @Override
    public void onBudgetRefunded(int tokens) {
        budgetRefunded.add(tokens);
    }

    @Override
    public void onBudgetEmpty() {
        budgetEmpty.add(1);
    }

    @Override
    public void onBudgetWait(long millis) {
        budgetWait.record(millis);
    }

    @Override
    public void onSlotWait(long millis) {
        slotWait.record(millis);
    }

    @Override
    public void onLimitCut(AdaptiveLimit.Signal signal, double before, double after) {
        limitCuts.add(1, Attributes.of(SIGNAL, lower(signal)));
    }

    @Override
    public void onLimitQuietReset() {
        limitQuietResets.add(1);
    }

    @Override
    public void onLimitGrowthSkipped() {
        limitGrowthSkipped.add(1);
    }

    @Override
    public void onLimitFirstFull(long millisSinceStart) {
        timeToFullLimit.record(millisSinceStart);
    }
}
