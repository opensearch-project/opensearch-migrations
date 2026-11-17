package org.opensearch.migrations.bulkload.common.retry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;

/**
 * Retry tokens shared by every bulk batch a process sends to one target, so that many batches failing at once can't
 * multiply the retry load on a target that is already struggling. It is the token-bucket retry quota of the AWS SDKs'
 * "standard" retry mode (AWS SDKs and Tools Reference Guide, "Retry behavior"), with a time-based refill added.
 *
 * <h2>State</h2>
 * The balance {@code B} is a real number, {@code 0 <= B <= C}, where {@code C} is the capacity
 * ({@value #DEFAULT_CAPACITY} by default). Initially {@code B = C}. There is also a first-in, first-out queue of
 * retries waiting for tokens.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Refill.</b> {@code B} grows continuously at {@value #REFILL_TOKENS_PER_SECOND} token per second, never
 *       above {@code C}.</li>
 *   <li><b>Spending.</b> A retry costs {@code c} tokens ({@link BulkErrorClass#cost()}: 5 for a throttle, 10 for a
 *       transient failure; {@code c <= C}). It proceeds at once if the queue is empty and {@code B >= c}, and then
 *       {@code B := B - c}. Otherwise it joins the end of the queue. Whenever {@code B} changes, queued retries are
 *       served in order while the first one's cost is covered. A retry never fails for lack of tokens; the work
 *       item's lease bounds how long it waits.</li>
 *   <li><b>Credits.</b> When one of a batch's requests succeeds, {@code B := min(C, B + c)} if that request was a
 *       retry that cost {@code c} (the retry is refunded), and {@code B := min(C, B + }
 *       {@value #FIRST_TRY_SUCCESS_REWARD}{@code )} if it was the batch's first attempt.</li>
 *   <li>A retry that is cancelled after its tokens were taken, but before it was told, gets them back. A retry
 *       cancelled while queued leaves the queue.</li>
 * </ul>
 * First attempts never draw on the budget; ramping up is the {@link AdaptiveLimit}'s job.
 *
 * <h2>What it bounds</h2>
 * Only retries that fail keep their cost. Over any interval of {@code t} seconds, the tokens spent on retries that
 * fail are therefore at most {@code C + t + s}, where {@code s} is the number of first-attempt successes in the
 * interval, and since every retry costs at least 5, at most {@code (C + t + s) / 5} retries fail. Over a long
 * interval that is a rate of at most {@code (1 + s/t) / 5} failed retries per second: a target that fails most
 * requests sees retries fall to about the refill rate, however many batches are failing.
 *
 * <p>{@code retryBudgetEmpty} counts the moments a retry had to wait while no other retry was waiting, that is, the
 * start of each period in which retries wait; {@code B} may be above zero then but below the retry's cost.
 * Thread-safe: retries ask for tokens from Reactor and Netty threads.
 */
@Slf4j
public class RetryBudget {
    public static final int DEFAULT_CAPACITY = 500;
    static final int FIRST_TRY_SUCCESS_REWARD = 1;
    static final int REFILL_TOKENS_PER_SECOND = 1;

    private final int capacity;
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private double tokens;
    private long lastRefillMillis;
    private Disposable scheduledDrain;
    private volatile BulkFlowMetrics metrics = BulkFlowMetrics.NONE;

    private static final class Waiter {
        final int cost;
        final MonoSink<Void> sink;
        boolean granted;

        Waiter(int cost, MonoSink<Void> sink) {
            this.cost = cost;
            this.sink = sink;
        }
    }

    public RetryBudget() {
        this(DEFAULT_CAPACITY);
    }

    public RetryBudget(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillMillis = now();
    }

    // Read from the parallel scheduler on every use so a test's virtual time replaces it.
    private static long now() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }

    public void setMetrics(BulkFlowMetrics metrics) {
        this.metrics = metrics;
    }

    public int capacity() {
        return capacity;
    }

    /** Whole tokens available now. */
    public synchronized int tokens() {
        refill();
        return (int) tokens;
    }

    /** Retries waiting for tokens. */
    public synchronized int waiters() {
        return waiters.size();
    }

    /**
     * Completes (empty) once {@code cost} tokens have been taken for a retry. Cancelling it while it waits leaves
     * the budget as it was.
     */
    public Mono<Void> acquire(int cost) {
        if (cost > capacity) {
            throw new IllegalArgumentException("cost " + cost + " exceeds the budget's capacity " + capacity);
        }
        return Mono.create(sink -> {
            var waiter = new Waiter(cost, sink);
            boolean granted;
            boolean becameEmpty = false;
            synchronized (this) {
                refill();
                granted = waiters.isEmpty() && tokens >= cost;
                if (granted) {
                    tokens -= cost;
                } else {
                    becameEmpty = waiters.isEmpty();
                    waiters.addLast(waiter);
                    scheduleDrain();
                }
            }
            if (becameEmpty) {
                metrics.onBudgetEmpty();
                log.atWarn().setMessage("The bulk retry budget is out of tokens; retries wait for it to refill at {} "
                    + "per second").addArgument(REFILL_TOKENS_PER_SECOND).log();
            }
            if (granted) {
                sink.success();
            } else {
                sink.onCancel(() -> {
                    synchronized (this) {
                        if (!waiters.remove(waiter) && waiter.granted) {
                            // Granted, but cancelled before it heard: the retry won't happen, so give the tokens back.
                            tokens = Math.min(capacity, tokens + waiter.cost);
                        }
                    }
                    drain();
                });
            }
        });
    }

    /**
     * A batch's request succeeded. {@code retryCost} is what its last retry cost (0 if this was its first
     * attempt): a retry that worked gets its tokens back, and a first-try success adds one.
     */
    public void onSuccess(int retryCost) {
        if (retryCost > 0) {
            metrics.onBudgetRefunded(retryCost);
        }
        synchronized (this) {
            refill();
            tokens = Math.min(capacity, tokens + (retryCost > 0 ? retryCost : FIRST_TRY_SUCCESS_REWARD));
        }
        drain();
    }

    private void refill() {
        long now = now();
        long elapsed = now - lastRefillMillis;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * REFILL_TOKENS_PER_SECOND / 1000.0);
            lastRefillMillis = now;
        }
    }

    /** Hand tokens to waiters in arrival order, then wake again when the next one can be served. */
    private void drain() {
        var granted = new ArrayList<Waiter>();
        synchronized (this) {
            refill();
            while (!waiters.isEmpty() && tokens >= waiters.peekFirst().cost) {
                var waiter = waiters.removeFirst();
                tokens -= waiter.cost;
                waiter.granted = true;
                granted.add(waiter);
            }
            scheduleDrain();
        }
        granted.forEach(waiter -> waiter.sink.success());
    }

    // Called holding the lock.
    private void scheduleDrain() {
        if (scheduledDrain != null && !scheduledDrain.isDisposed()) {
            scheduledDrain.dispose();
        }
        scheduledDrain = null;
        if (waiters.isEmpty()) {
            return;
        }
        double missing = waiters.peekFirst().cost - tokens;
        long delayMillis = Math.max(1, (long) Math.ceil(missing * 1000.0 / REFILL_TOKENS_PER_SECOND));
        scheduledDrain = Schedulers.parallel().schedule(this::drain, delayMillis, TimeUnit.MILLISECONDS);
    }
}
