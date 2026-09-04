package org.opensearch.migrations.replay.lifecycle;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayWorkId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Owns generation-scoped work ledgers and publishes the minimum safe source-time watermark.
 */
public final class ReplayProgressController implements SourcePartitionLifecycleListener {
    public interface WorkToken extends AutoCloseable {
        ReplayWorkId workId();

        SourcePartitionKey partition();

        CompletionStage<Void> settled();

        @Override
        void close();
    }

    @Value
    @Accessors(fluent = true)
    public static class Snapshot {
        int assignedPartitions;
        int outstandingWork;
        @NonNull Instant settledWatermark;
    }

    private static final class WorkEntry {
        private final ReplayWorkId workId;
        private final Instant sourceTime;
        private final CompletionGate<Void> completion = new CompletionGate<>();
        private boolean settled;

        private WorkEntry(ReplayWorkId workId, Instant sourceTime) {
            this.workId = workId;
            this.sourceTime = sourceTime;
        }
    }

    private static final class PartitionProgress {
        private final Deque<WorkEntry> admitted = new ArrayDeque<>();
        private Instant settledWatermark = Instant.MIN;
        private Instant idleWatermark = Instant.MIN;
        private boolean revoking;

        private Instant constrainingWatermark() {
            if (admitted.isEmpty()) {
                return later(settledWatermark, idleWatermark);
            }
            if (settledWatermark.equals(Instant.MIN)) {
                return admitted.peekFirst().sourceTime;
            }
            return settledWatermark;
        }
    }

    private final Executor ownerExecutor;
    private final ReplayReadGate readGate;
    private final Map<SourcePartitionKey, PartitionProgress> partitions = new LinkedHashMap<>();
    private final AtomicInteger outstandingSnapshot = new AtomicInteger();
    private final AtomicReference<Snapshot> snapshot =
        new AtomicReference<>(new Snapshot(0, 0, Instant.MIN));
    private Instant publishedWatermark = Instant.MIN;
    private Instant lastReplayClock = Instant.MIN;

    public ReplayProgressController(
        @NonNull Executor ownerExecutor,
        @NonNull ReplayReadGate readGate
    ) {
        this.ownerExecutor = ownerExecutor;
        this.readGate = readGate;
    }

    @Override
    public void onAssigned(@NonNull Collection<SourcePartitionKey> assigned) {
        ownerExecutor.execute(() -> {
            assigned.forEach(partition ->
                partitions.computeIfAbsent(partition, ignored -> {
                    var progress = new PartitionProgress();
                    progress.idleWatermark = lastReplayClock;
                    return progress;
                })
            );
            publish();
        });
    }

    @Override
    public void onRevoked(@NonNull Collection<SourcePartitionKey> revoked) {
        ownerExecutor.execute(() -> {
            for (var partition : revoked) {
                var progress = partitions.get(partition);
                if (progress == null) {
                    continue;
                }
                progress.revoking = true;
                if (progress.admitted.isEmpty()) {
                    partitions.remove(partition);
                }
            }
            publish();
        });
    }

    public CompletionStage<WorkToken> admit(
        @NonNull SourcePartitionKey partition,
        @NonNull ReplayWorkId workId,
        @NonNull Instant sourceTime
    ) {
        var completion = new CompletableFuture<WorkToken>();
        ownerExecutor.execute(() -> {
            var progress = partitions.computeIfAbsent(partition, ignored -> new PartitionProgress());
            if (progress.revoking) {
                completion.completeExceptionally(
                    new IllegalStateException("source partition generation is revoking: " + partition)
                );
                return;
            }
            if (!progress.admitted.isEmpty() && sourceTime.isBefore(progress.admitted.peekLast().sourceTime)) {
                completion.completeExceptionally(
                    new IllegalArgumentException(
                        "source work must be admitted in nondecreasing time order for " + partition
                    )
                );
                return;
            }
            var entry = new WorkEntry(workId, sourceTime);
            progress.admitted.addLast(entry);
            outstandingSnapshot.incrementAndGet();
            completion.complete(new OwnedWorkToken(partition, entry));
            publish();
        });
        return completion.minimalCompletionStage();
    }

    /**
     * Advances only partitions that have no admitted work. Active work therefore freezes its
     * partition's contribution to the global minimum.
     */
    public void advanceIdlePartitions(@NonNull Instant replayClock) {
        ownerExecutor.execute(() -> {
            lastReplayClock = later(lastReplayClock, replayClock);
            partitions.values().stream()
                .filter(progress -> progress.admitted.isEmpty())
                .forEach(progress -> progress.idleWatermark = later(progress.idleWatermark, lastReplayClock));
            publish();
        });
    }

    public boolean isWorkOutstanding() {
        return outstandingSnapshot.get() > 0;
    }

    public Snapshot currentSnapshot() {
        return snapshot.get();
    }

    private void publish() {
        var minimum = partitions.values().stream()
            .map(PartitionProgress::constrainingWatermark)
            .filter(watermark -> !watermark.equals(Instant.MIN))
            .min(Instant::compareTo)
            .orElse(Instant.MIN);
        if (!minimum.equals(Instant.MIN) && !minimum.equals(publishedWatermark)) {
            publishedWatermark = minimum;
            readGate.advanceTo(minimum);
        }
        snapshot.set(new Snapshot(partitions.size(), outstandingSnapshot.get(), minimum));
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private final class OwnedWorkToken implements WorkToken {
        private final SourcePartitionKey partition;
        private final WorkEntry entry;
        private final AtomicBoolean closeRequested = new AtomicBoolean();

        private OwnedWorkToken(SourcePartitionKey partition, WorkEntry entry) {
            this.partition = partition;
            this.entry = entry;
        }

        @Override
        public ReplayWorkId workId() {
            return entry.workId;
        }

        @Override
        public SourcePartitionKey partition() {
            return partition;
        }

        @Override
        public CompletionStage<Void> settled() {
            return entry.completion.stage();
        }

        @Override
        public void close() {
            if (closeRequested.compareAndSet(false, true)) {
                ownerExecutor.execute(this::settle);
            }
        }

        private void settle() {
            var progress = partitions.get(partition);
            if (progress == null) {
                throw new IllegalStateException("source partition was retired before work settled: " + partition);
            }
            if (entry.settled) {
                return;
            }
            entry.settled = true;
            entry.completion.complete(null);
            outstandingSnapshot.decrementAndGet();
            while (!progress.admitted.isEmpty() && progress.admitted.peekFirst().settled) {
                progress.settledWatermark = progress.admitted.removeFirst().sourceTime;
            }
            if (progress.revoking && progress.admitted.isEmpty()) {
                partitions.remove(partition);
            }
            publish();
        }
    }
}
