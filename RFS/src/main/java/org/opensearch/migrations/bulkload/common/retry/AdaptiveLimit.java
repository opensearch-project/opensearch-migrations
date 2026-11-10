package org.opensearch.migrations.bulkload.common.retry;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;

/**
 * An adaptive limit on how many bulk requests one process has in flight to one target, modeled on TCP congestion
 * control: slow start and congestion avoidance (RFC 5681) and growth only while the limit is in use (RFC 7661).
 * Processes don't coordinate; additive increase with multiplicative decrease is what lets independent senders share
 * a bottleneck (Chiu and Jain, 1989).
 *
 * <h2>State</h2>
 * <ul>
 *   <li>{@code M}: the maximum, an integer {@code >= 1} ({@code --max-connections}).</li>
 *   <li>{@code L}: the limit, a real number, {@code 1/32 <= L <= M} ({@link #MIN_LIMIT}). Initially {@code L = 1}.</li>
 *   <li>{@code T}: the threshold between the two growth rules, {@code 1 <= T <= M}. Initially {@code T = M}.</li>
 *   <li>{@code P = max(1, floor(L))}: the number of slots.</li>
 *   <li>{@code R}: a moving average of how long a slot is held (each release weighs 1/8).</li>
 *   <li>{@code n}: requests holding a slot; and a first-in, first-out queue of requests waiting for one.</li>
 * </ul>
 *
 * <h2>Admission</h2>
 * A request takes a slot at once if the queue is empty, {@code n < P}, and pacing allows (below); otherwise it joins
 * the end of the queue. Whenever a slot is released, and whenever a paced interval ends, queued requests take slots,
 * in order, while {@code n < P} and pacing allows. A slot is held for one HTTP attempt, so a batch waiting to retry
 * holds none. Requests are numbered in the order they take slots.
 *
 * <p><b>Pacing.</b> While {@code L < 1} there is one slot, and after each release it stays free for
 * {@code (1/L - 1) * R}: a process sends about {@code L} requests per hold time, which is how the limit keeps
 * shrinking a process's load once it is down to one request at a time. At {@code L >= 1} there is no pacing.
 *
 * <h2>Growth</h2>
 * When a request succeeds and the limit was its bottleneck (below), the limit grows:
 * <pre>
 *   L := min(1, L + 1/32)   if L &lt; 1
 *   L := min(M, L + 1)      if 1 &lt;= L &lt; T   (slow start)
 *   L := min(M, L + 1/L)    if L &gt;= max(1, T)  (congestion avoidance)
 * </pre>
 * Below 1 the step is the floor, not {@code 1/L}, which would add more than 1 at once; from {@code 1/32} back to 1
 * takes 31 successes.
 * A round of {@code P} successes therefore about doubles {@code L} in slow start and adds about 1 in congestion
 * avoidance. The limit was a request's bottleneck if the request took the last free slot ({@code n} became
 * {@code P}), or if before the request was released another request took the last free slot or had to wait. If not,
 * the success leaves {@code L} unchanged and is counted as {@code bulkLimitGrowthSkipped}: a limit that was never
 * reached wasn't tested, and growing it would let a process burst past anything the target has accepted.
 *
 * <h2>Cuts</h2>
 * A throttle is a 429, a 200 or 201 with an item that failed with {@code rejected_execution_exception} (a full write
 * queue) or {@code circuit_breaking_exception} (too little heap), or no response within the bulk response timeout
 * ({@link Signal#isThrottle()}). A throttle on a request that
 * took its slot after the most recent cut (or on any request, before the first cut) sets
 * <pre>
 *   T := max(1, L/2)
 *   L := max(1/32, u * L),   u drawn uniformly from [0.5, 0.7)
 * </pre>
 * with {@code L} on the right the value before the cut. A process always has one slot, but below 1 it is paced, so
 * cuts keep reducing its load until {@code L = 1/32}: one request per {@code 31 R}. A throttle on a request that
 * took its slot before the most recent cut changes neither value: one overload, reported by every request then in
 * flight, causes one cut. Because {@code u >= 0.5}, the limit after a cut is at least the new threshold, so after a
 * cut the limit grows by the below-1 rule or the congestion-avoidance rule; the slow-start rule applies only at the
 * start and after a quiet-period reset. Drawing {@code u} at random keeps processes throttled by the same overload
 * from cutting to the same value and growing back in step.
 *
 * <h2>Quiet-period reset</h2>
 * When a request succeeds, if {@code T < M} and no throttle (counted or not) has been seen for {@link #QUIET_PERIOD}
 * (measured from the last throttle, or from creation if there has been none), then {@code T := M}, so the limit grows
 * by the slow-start rule again until the next cut. This is checked before the growth step, whether or not the limit
 * was the request's bottleneck.
 *
 * <p>Any other outcome (another 5xx, a dropped connection, a cancelled request) releases the slot and changes nothing
 * else. Thread-safe.
 */
@Slf4j
public class AdaptiveLimit {
    public static final Duration QUIET_PERIOD = Duration.ofMinutes(5);
    /** The lowest the limit goes. Below 1 a process has one slot and paces its requests (see the class comment). */
    public static final double MIN_LIMIT = 1.0 / 32;
    /** Weight of the newest sample in the moving average of slot hold times used for pacing. */
    static final double RTT_WEIGHT = 0.125;
    static final double MIN_CUT = 0.5;
    static final double MAX_CUT = 0.7;

    /** How a request that held a slot ended. The three throttle kinds all cut the limit. */
    public enum Signal {
        SUCCESS, THROTTLED_429, ADMISSION_REJECTED, TIMEOUT, OTHER_FAILURE, CANCELLED;

        public boolean isThrottle() {
            return this == THROTTLED_429 || this == ADMISSION_REJECTED || this == TIMEOUT;
        }
    }

    private final int max;
    private final DoubleSupplier random;
    private final ArrayDeque<MonoSink<Slot>> waiters = new ArrayDeque<>();
    private double limit = 1;
    private double threshold;
    private int inFlight;
    private long nextSequence;
    private long firstSequenceAfterCut;
    // Requests sent before this were sent while every slot was in use (or requests were waiting for one).
    private long fullBeforeSequence;
    private long lastThrottleMillis;
    private final long createdMillis;
    private boolean reachedMax;
    // Pacing below a limit of 1: a moving average of how long a slot is held, and when the next one may be taken.
    private double rttMillis = -1;
    private long nextAdmitMillis;
    private Disposable scheduledAdmit;
    private volatile BulkFlowMetrics metrics = BulkFlowMetrics.NONE;

    /** One request's hold on the limit. Release it once; recording is optional. */
    public final class Slot {
        private final long sequence;
        private final long takenMillis;
        private boolean released;

        private Slot(long sequence, long takenMillis) {
            this.sequence = sequence;
            this.takenMillis = takenMillis;
        }

        public void release(Signal signal) {
            AdaptiveLimit.this.release(this, signal);
        }
    }

    public AdaptiveLimit(int max) {
        this(max, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random numbers {@code r} in {@code [0, 1)}; each cut uses {@code u = 0.5 + 0.2 * r} */
    public AdaptiveLimit(int max, DoubleSupplier random) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be positive, got " + max);
        }
        this.max = max;
        this.random = random;
        this.threshold = max;
        this.lastThrottleMillis = now();
        this.createdMillis = lastThrottleMillis;
        this.reachedMax = max == 1;
    }

    public void setMetrics(BulkFlowMetrics metrics) {
        this.metrics = metrics;
    }

    // Read from the parallel scheduler on every use so a test's virtual time replaces it.
    private static long now() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }

    public int max() {
        return max;
    }

    public synchronized double limit() {
        return limit;
    }

    public synchronized double threshold() {
        return threshold;
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public synchronized int waiters() {
        return waiters.size();
    }

    private int permits() {
        return Math.max(1, (int) limit);
    }

    /** A slot, once one is free. Cancelling while waiting gives up the place in line. */
    public Mono<Slot> acquire() {
        return Mono.create(sink -> {
            Slot granted = null;
            synchronized (this) {
                if (waiters.isEmpty() && inFlight < permits() && now() >= nextAdmitMillis) {
                    granted = take();
                } else {
                    waiters.addLast(sink);
                    fullBeforeSequence = nextSequence;
                    scheduleAdmit();
                }
            }
            if (granted != null) {
                sink.success(granted);
            } else {
                sink.onCancel(() -> {
                    synchronized (this) {
                        waiters.remove(sink);
                    }
                });
            }
        });
    }

    // Called holding the lock.
    private Slot take() {
        inFlight++;
        var slot = new Slot(nextSequence++, now());
        if (inFlight >= permits()) {
            fullBeforeSequence = nextSequence;
        }
        return slot;
    }

    // Slot is deliberately a small handle; the limit owns the queue, pacing, metrics, and all synchronized state.
    @SuppressWarnings("java:S3398")
    private void release(Slot slot, Signal signal) {
        List<MonoSink<Slot>> handOff;
        List<Slot> handOffSlots;
        synchronized (this) {
            if (slot.released) {
                return;
            }
            slot.released = true;
            boolean bottleneck = slot.sequence < fullBeforeSequence;
            inFlight--;
            long now = now();
            long held = now - slot.takenMillis;
            rttMillis = rttMillis < 0 ? held : (1 - RTT_WEIGHT) * rttMillis + RTT_WEIGHT * held;
            if (signal == Signal.SUCCESS) {
                onSuccess(bottleneck);
            } else if (signal.isThrottle()) {
                onThrottle(slot, signal);
            }
            // Below 1 the single slot stays idle for (1/L - 1) average hold times after each release, so the
            // process sends about L requests per hold time.
            nextAdmitMillis = limit < 1 ? now + (long) (rttMillis * (1 / limit - 1)) : 0;
            handOff = new ArrayList<>();
            handOffSlots = new ArrayList<>();
            admitWaiters(handOff, handOffSlots);
        }
        for (int i = 0; i < handOff.size(); i++) {
            handOff.get(i).success(handOffSlots.get(i));
        }
    }

    // Called holding the lock: give slots to queued requests while there are slots and pacing allows.
    private void admitWaiters(List<MonoSink<Slot>> handOff, List<Slot> handOffSlots) {
        while (!waiters.isEmpty() && inFlight < permits() && now() >= nextAdmitMillis) {
            handOff.add(waiters.removeFirst());
            handOffSlots.add(take());
        }
        scheduleAdmit();
    }

    // Called holding the lock: if requests are waiting only because of pacing, wake when the next may go.
    private void scheduleAdmit() {
        if (scheduledAdmit != null) {
            scheduledAdmit.dispose();
            scheduledAdmit = null;
        }
        if (waiters.isEmpty() || inFlight >= permits()) {
            return;
        }
        long delay = Math.max(1, nextAdmitMillis - now());
        scheduledAdmit = Schedulers.parallel().schedule(() -> {
            var handOff = new ArrayList<MonoSink<Slot>>();
            var handOffSlots = new ArrayList<Slot>();
            synchronized (this) {
                admitWaiters(handOff, handOffSlots);
            }
            for (int i = 0; i < handOff.size(); i++) {
                handOff.get(i).success(handOffSlots.get(i));
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    // Called holding the lock.
    private void onSuccess(boolean bottleneck) {
        long now = now();
        if (threshold < max && now - lastThrottleMillis >= QUIET_PERIOD.toMillis()) {
            log.atInfo().setMessage("No bulk throttling for {}; growing the in-flight limit (now {}) quickly again")
                .addArgument(QUIET_PERIOD).addArgument(this::describeLimit).log();
            threshold = max;
            lastThrottleMillis = now;
            metrics.onLimitQuietReset();
        }
        if (!bottleneck) {
            metrics.onLimitGrowthSkipped();
            return;
        }
        if (limit < 1) {
            // 1/L would grow a small limit by a lot per success; below 1, grow by the floor instead.
            limit = Math.min(1, limit + MIN_LIMIT);
        } else {
            limit = Math.min(max, limit < threshold ? limit + 1 : limit + 1 / limit);
        }
        if (!reachedMax && limit >= max) {
            reachedMax = true;
            metrics.onLimitFirstFull(now - createdMillis);
        }
    }

    // Called holding the lock.
    private void onThrottle(Slot slot, Signal signal) {
        lastThrottleMillis = now();
        if (slot.sequence < firstSequenceAfterCut) {
            return;
        }
        double before = limit;
        threshold = Math.max(1, limit / 2);
        limit = Math.max(MIN_LIMIT, limit * (MIN_CUT + (MAX_CUT - MIN_CUT) * random.getAsDouble()));
        firstSequenceAfterCut = nextSequence;
        metrics.onLimitCut(signal, before, limit);
        log.atInfo().setMessage("The target throttled a bulk request ({}); cutting the in-flight limit from {} to {} "
                + "(threshold {})")
            .addArgument(signal)
            .addArgument(() -> String.format("%.2f", before))
            .addArgument(() -> String.format("%.2f", limit))
            .addArgument(() -> String.format("%.2f", threshold))
            .log();
    }

    private String describeLimit() {
        return String.format("%.2f", limit);
    }
}
