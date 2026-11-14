package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import lombok.Getter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * How long a failing bulk batch waits before its next attempt. It never gives up on its own: whether a document has
 * failed is decided per document by the client ({@code OpenSearchClient}), and the work item's lease bounds the
 * rest. Let {@code Cmax} be the longest wait ({@link #DEFAULT_MAX_BACKOFF}, 30 s).
 *
 * <p>When attempt {@code k + 1} of a batch fails ({@code k = 0, 1, 2, ...} retries so far) with error {@code e}:
 * <ol>
 *   <li>If {@code e} is {@link BulkErrorClass#AUTH}, the error is passed on, not retried.</li>
 *   <li>The wait is {@code d = min(RA, Cmax)} if the response carried {@code Retry-After} {@code RA} (seconds, or an
 *       HTTP date, which counts as 0 once past). Otherwise it is "full jitter":
 *       {@code d = floor(r * min(Cmax, }{@value #BASE_DELAY_MILLIS}{@code  ms * 2^k))} with {@code r} drawn
 *       uniformly from {@code [0, 1)}. Its mean is half the bound: 1, 2, 4 and 8 s for {@code k = 0..3}, then
 *       15 s. Drawing the whole wait at random spreads out batches that failed together (AWS Architecture Blog,
 *       "Exponential Backoff And Jitter").</li>
 *   <li>Take {@link BulkErrorClass#cost()} tokens from the {@link RetryBudget}, waiting for them if there aren't
 *       enough.</li>
 *   <li>Wait {@code d}, then send attempt {@code k + 2} with the operations still pending.</li>
 * </ol>
 * When the batch's last attempt succeeds, {@link #onSuccess} credits the budget.
 */
public class BulkRetryPolicy {
    public static final long BASE_DELAY_MILLIS = 2_000;
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);

    private final RetryBudget budget;
    private final DoubleSupplier random;
    // Installed after client construction and read by asynchronous retry callbacks.
    @SuppressWarnings("java:S1450")
    private volatile BulkFlowMetrics metrics = BulkFlowMetrics.NONE;

    /** The retry state of one batch: what its last retry cost, so a success can refund it. */
    public static class Batch {
        @Getter
        private volatile int lastRetryCost;
    }

    public BulkRetryPolicy(RetryBudget budget) {
        this(budget, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random numbers in {@code [0, 1)} for the jitter */
    public BulkRetryPolicy(RetryBudget budget, DoubleSupplier random) {
        this.budget = budget;
        this.random = random;
    }

    public void setMetrics(BulkFlowMetrics metrics) {
        this.metrics = metrics;
    }

    public RetryBudget budget() {
        return budget;
    }

    /** The batch's last attempt succeeded. */
    public void onSuccess(Batch batch) {
        budget.onSuccess(batch.lastRetryCost);
    }

    // Read from the parallel scheduler on every use so a test's virtual time replaces it.
    private static long now() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }

    long backoffMillis(long retriesSoFar, Duration maxBackoff) {
        long cap = maxBackoff.toMillis();
        long exponential = retriesSoFar >= 30 ? cap : Math.min(cap, BASE_DELAY_MILLIS << retriesSoFar);
        return (long) (exponential * random.getAsDouble());
    }

    /** A {@link Retry} for one batch. */
    public Retry retryFor(Batch batch, Duration maxBackoff) {
        return Retry.from(signals ->
            // concatMap: a single Mono has at most one failure outstanding, since it isn't resubscribed until the
            // companion emits.
            signals.concatMap(signal -> {
                // Reactor may reuse the signal, so read it now.
                var failure = signal.failure();
                long retriesSoFar = signal.totalRetries();
                var errorClass = BulkErrorClass.of(failure);
                if (!errorClass.retryable()) {
                    return Mono.error(failure);
                }
                var retryAfter = BulkErrorClass.retryAfter(failure, Instant.ofEpochMilli(now()));
                long delay = retryAfter
                    .map(after -> Math.min(after.toMillis(), maxBackoff.toMillis()))
                    .orElseGet(() -> backoffMillis(retriesSoFar, maxBackoff));
                batch.lastRetryCost = errorClass.cost();
                long waitStart = now();
                return budget.acquire(errorClass.cost())
                    .doOnSuccess(ignored -> {
                        metrics.onBudgetWait(now() - waitStart);
                        metrics.onBudgetSpent(errorClass, errorClass.cost());
                        metrics.onRetry(errorClass, delay, retryAfter.isPresent());
                    })
                    // The companion must emit to trigger the retry: completing empty would complete the bulk Mono
                    // empty, counting the batch as done. Mono.delay also moves the resubscription off the
                    // Netty event loop.
                    .then(Mono.delay(Duration.ofMillis(delay), Schedulers.parallel()));
            }));
    }
}
