package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

public final class AsyncPermitPool {
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

        int cost();

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
    private final Executor ownerExecutor;
    private final Metrics metrics;
    private final LongSupplier nanoTime;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int available;
    private boolean closed;
    private CancellationException closeCause;

    public AsyncPermitPool(int capacity, @NonNull Executor ownerExecutor) {
        this(capacity, ownerExecutor, Metrics.NOOP);
    }

    public AsyncPermitPool(int capacity, @NonNull Executor ownerExecutor, @NonNull Metrics metrics) {
        this(capacity, ownerExecutor, metrics, System::nanoTime);
    }

    AsyncPermitPool(
        int capacity,
        @NonNull Executor ownerExecutor,
        @NonNull Metrics metrics,
        @NonNull LongSupplier nanoTime
    ) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.available = capacity;
        this.ownerExecutor = ownerExecutor;
        this.metrics = metrics;
        this.nanoTime = nanoTime;
        metrics.availableChanged(capacity);
    }

    public CompletionStage<Permit> acquire(@NonNull ReplayRequestId requestId, int cost) {
        if (cost <= 0 || cost > capacity) {
            throw new IllegalArgumentException("cost must be between one and capacity");
        }
        var completion = new CompletableFuture<Permit>();
        ownerExecutor.execute(() -> {
            if (closed) {
                metrics.cancelled(1);
                completion.completeExceptionally(closeCause);
                return;
            }
            waiters.addLast(new Waiter(requestId, cost, completion));
            metrics.queuedChanged(1);
            drainWaiters();
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Integer> cancel(
        @NonNull Predicate<ReplayRequestId> selector,
        @NonNull CancellationException cause
    ) {
        var completion = new CompletableFuture<Integer>();
        ownerExecutor.execute(() -> {
            int cancelled = 0;
            var iterator = waiters.iterator();
            while (iterator.hasNext()) {
                var waiter = iterator.next();
                if (selector.test(waiter.requestId())) {
                    iterator.remove();
                    waiter.completion().completeExceptionally(cause);
                    cancelled++;
                }
            }
            if (cancelled > 0) {
                metrics.queuedChanged(-cancelled);
                metrics.cancelled(cancelled);
            }
            completion.complete(cancelled);
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> close(@NonNull CancellationException cause) {
        var completion = new CompletableFuture<Void>();
        ownerExecutor.execute(() -> {
            if (!closed) {
                closed = true;
                closeCause = cause;
                var queued = waiters.size();
                while (!waiters.isEmpty()) {
                    waiters.removeFirst().completion().completeExceptionally(cause);
                }
                if (queued > 0) {
                    metrics.queuedChanged(-queued);
                    metrics.cancelled(queued);
                }
                metrics.availableChanged(-available);
            }
            completion.complete(null);
        });
        return completion.minimalCompletionStage();
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
        public int cost() {
            return cost;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                metrics.permitHeld(Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - acquiredNanos)));
                ownerExecutor.execute(() -> {
                    available += cost;
                    if (available > capacity) {
                        throw new IllegalStateException("released more permits than the pool owns");
                    }
                    if (!closed) {
                        metrics.availableChanged(cost);
                    }
                    drainWaiters();
                });
            }
        }
    }
}
