package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ReplayIntakeInput . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

public final class TargetAttemptPermitProvider {
    public sealed interface Input extends ReplayIntakeInput permits
        AcquireRequested,
        CancelRequested,
        CloseRequested,
        PermitReleased {}

    private record AcquireRequested(
        @NonNull ReplayRequestId requestId,
        int cost,
        @NonNull CompletableFuture<Permit> completion
    ) implements Input {}

    private record CancelRequested(
        @NonNull Predicate<ReplayRequestId> selector,
        @NonNull CancellationException cause,
        @NonNull CompletableFuture<Integer> completion
    ) implements Input {}

    private record CloseRequested(
        @NonNull CancellationException cause,
        @NonNull CompletableFuture<Void> completion
    ) implements Input {}

    private record PermitReleased(int cost, long acquiredNanos) implements Input {}

    public interface Metrics {
        Metrics NOOP = new Metrics() {
            @Override
            public void availableChanged(int delta) {
                // Metrics are optional for non-production pool instances.
            }

            @Override
            public void queuedChanged(int delta) {
                // Metrics are optional for non-production pool instances.
            }

            @Override
            public void permitHeld(Duration duration) {
                // Metrics are optional for non-production pool instances.
            }

            @Override
            public void cancelled(int count) {
                // Metrics are optional for non-production pool instances.
            }
        };

        void availableChanged(int delta);

        void queuedChanged(int delta);

        void permitHeld(Duration duration);

        void cancelled(int count);
    }

    public interface Permit extends AutoCloseable {
        ReplayRequestId requestId();

        @Override
        void close();
    }

    private static final class Waiter {
        private final ReplayRequestId requestId;
        private final int cost;
        private final CompletableFuture<Permit> completion;

        private Waiter(ReplayRequestId requestId, int cost, CompletableFuture<Permit> completion) {
            this.requestId = requestId;
            this.cost = cost;
            this.completion = completion;
        }

        private ReplayRequestId requestId() {
            return requestId;
        }

        private int cost() {
            return cost;
        }

        private CompletableFuture<Permit> completion() {
            return completion;
        }
    }

    private final int capacity;
    private final Consumer<Input> ownerInputSink;
    private final OwnerThreadGuard ownerThreadGuard =
        new OwnerThreadGuard("asynchronous permit pool");
    private final Metrics metrics;
    private final LongSupplier nanoTime;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int available;
    private boolean closed;
    private CancellationException closeCause;

    public TargetAttemptPermitProvider(int capacity, @NonNull Executor ownerExecutor) {
        this(capacity, ownerExecutor, Metrics.NOOP);
    }

    public TargetAttemptPermitProvider(int capacity, @NonNull Executor ownerExecutor, @NonNull Metrics metrics) {
        this(capacity, ownerExecutor, metrics, System::nanoTime);
    }

    public TargetAttemptPermitProvider(
        int capacity,
        @NonNull Consumer<Input> ownerInputSink,
        @NonNull Metrics metrics
    ) {
        this(capacity, ownerInputSink, metrics, System::nanoTime);
    }

    TargetAttemptPermitProvider(
        int capacity,
        @NonNull Executor ownerExecutor,
        @NonNull Metrics metrics,
        @NonNull LongSupplier nanoTime
    ) {
        validateCapacity(capacity);
        this.capacity = capacity;
        this.available = capacity;
        this.ownerInputSink = input -> ownerExecutor.execute(() -> apply(input));
        this.metrics = metrics;
        this.nanoTime = nanoTime;
        metrics.availableChanged(capacity);
    }

    TargetAttemptPermitProvider(
        int capacity,
        @NonNull Consumer<Input> ownerInputSink,
        @NonNull Metrics metrics,
        @NonNull LongSupplier nanoTime
    ) {
        validateCapacity(capacity);
        this.capacity = capacity;
        this.available = capacity;
        this.ownerInputSink = ownerInputSink;
        this.metrics = metrics;
        this.nanoTime = nanoTime;
        metrics.availableChanged(capacity);
    }

    private static void validateCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }

    public CompletionStage<Permit> acquire(@NonNull ReplayRequestId requestId, int cost) {
        if (cost <= 0 || cost > capacity) {
            throw new IllegalArgumentException("cost must be between one and capacity");
        }
        var completion = new CompletableFuture<Permit>();
        ownerInputSink.accept(new AcquireRequested(requestId, cost, completion));
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Integer> cancel(
        @NonNull Predicate<ReplayRequestId> selector,
        @NonNull CancellationException cause
    ) {
        var completion = new CompletableFuture<Integer>();
        ownerInputSink.accept(new CancelRequested(selector, cause, completion));
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> close(@NonNull CancellationException cause) {
        var completion = new CompletableFuture<Void>();
        ownerInputSink.accept(new CloseRequested(cause, completion));
        return completion.minimalCompletionStage();
    }

    void apply(@NonNull Input input) {
        ownerThreadGuard.guard(() -> {
            switch (input) {
                case AcquireRequested acquire -> applyAcquire(acquire);
                case CancelRequested cancel -> applyCancel(cancel);
                case CloseRequested close -> applyClose(close);
                case PermitReleased release -> applyRelease(release);
            }
        }).run();
    }

    private void applyAcquire(AcquireRequested acquire) {
        var completion = acquire.completion();
        if (closed) {
            metrics.cancelled(1);
            completion.completeExceptionally(closeCause);
            return;
        }
        waiters.addLast(new Waiter(acquire.requestId(), acquire.cost(), completion));
        metrics.queuedChanged(1);
        drainWaiters();
    }

    private void applyCancel(CancelRequested cancel) {
        int cancelled = 0;
        var iterator = waiters.iterator();
        while (iterator.hasNext()) {
            var waiter = iterator.next();
            if (cancel.selector().test(waiter.requestId())) {
                iterator.remove();
                waiter.completion().completeExceptionally(cancel.cause());
                cancelled++;
            }
        }
        if (cancelled > 0) {
            metrics.queuedChanged(-cancelled);
            metrics.cancelled(cancelled);
        }
        cancel.completion().complete(cancelled);
    }

    private void applyClose(CloseRequested close) {
        if (!closed) {
            closed = true;
            closeCause = close.cause();
            var queued = waiters.size();
            while (!waiters.isEmpty()) {
                waiters.removeFirst().completion().completeExceptionally(close.cause());
            }
            if (queued > 0) {
                metrics.queuedChanged(-queued);
                metrics.cancelled(queued);
            }
            metrics.availableChanged(-available);
        }
        close.completion().complete(null);
    }

    private void applyRelease(PermitReleased release) {
        metrics.permitHeld(Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - release.acquiredNanos())));
        available += release.cost();
        if (available > capacity) {
            throw new IllegalStateException("released more permits than the pool owns");
        }
        if (!closed) {
            metrics.availableChanged(release.cost());
        }
        drainWaiters();
    }

    private void drainWaiters() {
        while (!waiters.isEmpty() && waiters.peekFirst().cost() <= available) {
            var waiter = waiters.removeFirst();
            metrics.queuedChanged(-1);
            available -= waiter.cost();
            metrics.availableChanged(-waiter.cost());
            waiter.completion().complete(
                new OwnedPermit(waiter.requestId(), waiter.cost(), nanoTime.getAsLong())
            );
        }
    }

    private final class OwnedPermit implements Permit {
        private final ReplayRequestId requestId;
        private final int cost;
        private final long acquiredNanos;
        private final AtomicBoolean released = new AtomicBoolean();

        private OwnedPermit(ReplayRequestId requestId, int cost, long acquiredNanos) {
            this.requestId = requestId;
            this.cost = cost;
            this.acquiredNanos = acquiredNanos;
        }

        @Override
        public ReplayRequestId requestId() {
            return requestId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                ownerInputSink.accept(new PermitReleased(cost, acquiredNanos));
            }
        }
    }
}

*/
// REBUILD-LIMBO-END(G5)