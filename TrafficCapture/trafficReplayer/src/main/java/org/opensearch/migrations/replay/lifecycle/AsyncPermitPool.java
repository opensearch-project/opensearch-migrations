package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

public final class AsyncPermitPool {
    public interface Permit extends AutoCloseable {
        ReplayRequestId requestId();

        int cost();

        @Override
        void close();
    }

    private record Waiter(
        ReplayRequestId requestId,
        int cost,
        CompletableFuture<Permit> completion
    ) {}

    private final int capacity;
    private final Executor ownerExecutor;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int available;
    private boolean closed;
    private CancellationException closeCause;

    public AsyncPermitPool(int capacity, @NonNull Executor ownerExecutor) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.available = capacity;
        this.ownerExecutor = ownerExecutor;
    }

    public CompletionStage<Permit> acquire(@NonNull ReplayRequestId requestId, int cost) {
        if (cost <= 0 || cost > capacity) {
            throw new IllegalArgumentException("cost must be between one and capacity");
        }
        var completion = new CompletableFuture<Permit>();
        ownerExecutor.execute(() -> {
            if (closed) {
                completion.completeExceptionally(closeCause);
                return;
            }
            waiters.addLast(new Waiter(requestId, cost, completion));
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
                while (!waiters.isEmpty()) {
                    waiters.removeFirst().completion().completeExceptionally(cause);
                }
            }
            completion.complete(null);
        });
        return completion.minimalCompletionStage();
    }

    private void drainWaiters() {
        while (!waiters.isEmpty() && waiters.peekFirst().cost() <= available) {
            var waiter = waiters.removeFirst();
            available -= waiter.cost();
            waiter.completion().complete(new OwnedPermit(waiter.requestId(), waiter.cost()));
        }
    }

    private final class OwnedPermit implements Permit {
        private final ReplayRequestId requestId;
        private final int cost;
        private final AtomicBoolean released = new AtomicBoolean();

        private OwnedPermit(ReplayRequestId requestId, int cost) {
            this.requestId = requestId;
            this.cost = cost;
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
                ownerExecutor.execute(() -> {
                    available += cost;
                    if (available > capacity) {
                        throw new IllegalStateException("released more permits than the pool owns");
                    }
                    drainWaiters();
                });
            }
        }
    }
}
