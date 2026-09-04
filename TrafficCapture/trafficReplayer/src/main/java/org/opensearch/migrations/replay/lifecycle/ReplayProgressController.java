package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

public final class ReplayProgressController {
    public interface WorkToken extends AutoCloseable {
        ReplayRequestId requestId();

        CompletionStage<Void> settled();

        @Override
        void close();
    }

    private static final class WorkEntry {
        private final ReplayRequestId requestId;
        private final Instant sourceTime;
        private final CompletionGate<Void> completion = new CompletionGate<>();
        private boolean settled;

        private WorkEntry(ReplayRequestId requestId, Instant sourceTime) {
            this.requestId = requestId;
            this.sourceTime = sourceTime;
        }
    }

    private final Duration epsilon;
    private final Executor ownerExecutor;
    private final Deque<WorkEntry> admitted = new ArrayDeque<>();
    private Instant settledWatermark = Instant.MIN;

    public ReplayProgressController(@NonNull Duration epsilon, @NonNull Executor ownerExecutor) {
        if (epsilon.isNegative()) {
            throw new IllegalArgumentException("epsilon must not be negative");
        }
        this.epsilon = epsilon;
        this.ownerExecutor = ownerExecutor;
    }

    public CompletionStage<WorkToken> admit(
        @NonNull ReplayRequestId requestId,
        @NonNull Instant sourceTime
    ) {
        var completion = new CompletableFuture<WorkToken>();
        ownerExecutor.execute(() -> {
            if (!admitted.isEmpty() && sourceTime.isBefore(admitted.peekLast().sourceTime)) {
                completion.completeExceptionally(
                    new IllegalArgumentException("source work must be admitted in nondecreasing time order")
                );
                return;
            }
            var entry = new WorkEntry(requestId, sourceTime);
            admitted.addLast(entry);
            completion.complete(new OwnedWorkToken(entry));
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Instant> settledWatermark() {
        var completion = new CompletableFuture<Instant>();
        ownerExecutor.execute(() -> completion.complete(settledWatermark));
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Instant> readFrontier() {
        var completion = new CompletableFuture<Instant>();
        ownerExecutor.execute(() -> completion.complete(
            settledWatermark.equals(Instant.MIN) ? Instant.MAX : settledWatermark.plus(epsilon)
        ));
        return completion.minimalCompletionStage();
    }

    private void settle(WorkEntry entry) {
        if (entry.settled) {
            return;
        }
        entry.settled = true;
        entry.completion.complete(null);
        while (!admitted.isEmpty() && admitted.peekFirst().settled) {
            settledWatermark = admitted.removeFirst().sourceTime;
        }
    }

    private final class OwnedWorkToken implements WorkToken {
        private final WorkEntry entry;
        private final AtomicBoolean closeRequested = new AtomicBoolean();

        private OwnedWorkToken(WorkEntry entry) {
            this.entry = entry;
        }

        @Override
        public ReplayRequestId requestId() {
            return entry.requestId;
        }

        @Override
        public CompletionStage<Void> settled() {
            return entry.completion.stage();
        }

        @Override
        public void close() {
            if (closeRequested.compareAndSet(false, true)) {
                ownerExecutor.execute(() -> settle(entry));
            }
        }
    }
}
