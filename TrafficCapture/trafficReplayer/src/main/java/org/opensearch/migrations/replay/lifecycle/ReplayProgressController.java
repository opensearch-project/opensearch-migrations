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
 * Controls how far replay intake may advance based on work already admitted for replay.
 *
 * <p>For each Kafka partition generation, the controller keeps admitted work in source order.
 * {@link #admit(SourcePartitionKey, ReplayWorkId, Instant)} returns a {@link WorkToken}; closing
 * that token reports that the associated replay work has reached its required final state. Work
 * may finish out of order, but the partition's completed frontier advances only by removing a
 * contiguous settled prefix from the admission queue.
 *
 * <p>The value supplied to {@link ReplayReadGate} is the minimum constraint across tracked
 * partition generations. A partition with outstanding work contributes the source time of its
 * oldest admitted work. An idle partition contributes the later of its completed frontier and the
 * replay clock supplied through {@link #advanceIdlePartitions(Instant)}. {@code ReplayReadGate}
 * applies the configured read-ahead allowance to that global minimum before permitting more source
 * input. Consequently, the {@link Snapshot#settledWatermark()} field is the currently published
 * global source-time constraint; while work is active it may identify the oldest outstanding work
 * rather than a timestamp through which every operation has completed.
 *
 * <p>The controller also owns the replay-quiescence interval. {@link #whenQuiescent()} completes
 * when every admitted token has settled. Partition revocation prevents new admissions for that
 * generation but retains its progress until existing tokens settle; partition retirement requires
 * that no admitted work remain.
 *
 * <p>This class does not reconstruct traffic, decide Kafka-record {@code Commit} or {@code Retain}
 * dispositions, submit Kafka offset commits, or own transaction resources. Those responsibilities
 * belong to the reconstruction, disposition-ledger, Kafka-source, and transaction components
 * described in {@code replayerProcessingAndCommitArchitecture.md}.
 */
public final class ReplayProgressController implements SourcePartitionLifecycleListener {
    public interface WorkToken extends AutoCloseable {
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
        private Instant admissionWatermark = Instant.MIN;
        private boolean revoking;

        private Instant constrainingWatermark() {
            if (admitted.isEmpty()) {
                return later(settledWatermark, idleWatermark);
            }
            return admitted.peekFirst().sourceTime;
        }
    }

    private record PartitionIdentity(@NonNull String sourceId, int partition) {}

    private final Executor ownerExecutor;
    private final OwnerThreadGuard ownerThreadGuard =
        new OwnerThreadGuard("replay progress controller");
    private final ReplayReadGate readGate;
    private final Map<SourcePartitionKey, PartitionProgress> partitions = new LinkedHashMap<>();
    private final Map<PartitionIdentity, Integer> endedGenerationWatermarks = new LinkedHashMap<>();
    private final AtomicInteger outstandingSnapshot = new AtomicInteger();
    private final AtomicReference<CompletionGate<Void>> quiescenceGate =
        new AtomicReference<>(completedGate());
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
        executeOnOwner(() -> {
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
        executeOnOwner(() -> {
            for (var partition : revoked) {
                endedGenerationWatermarks.merge(
                    identity(partition),
                    partition.sourceGeneration(),
                    Math::max
                );
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

    @Override
    public void onRetired(@NonNull Collection<SourcePartitionKey> retired) {
        executeOnOwner(() -> {
            for (var partition : retired) {
                var progress = partitions.get(partition);
                if (progress != null && !progress.admitted.isEmpty()) {
                    throw new IllegalStateException(
                        "source partition generation retired with replay work still admitted: " + partition
                    );
                }
                partitions.remove(partition);
                endedGenerationWatermarks.merge(
                    identity(partition),
                    partition.sourceGeneration(),
                    Math::max
                );
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
        executeOnOwner(() -> {
            var progress = partitions.get(partition);
            if (progress != null && progress.revoking) {
                completion.completeExceptionally(
                    new IllegalStateException("source partition generation is revoking: " + partition)
                );
                return;
            }
            if (isEnded(partition)) {
                completion.completeExceptionally(
                    new IllegalStateException("source partition generation already ended: " + partition)
                );
                return;
            }
            if (progress == null) {
                progress = new PartitionProgress();
                partitions.put(partition, progress);
            }
            progress.admissionWatermark = later(progress.admissionWatermark, sourceTime);
            var entry = new WorkEntry(workId, progress.admissionWatermark);
            progress.admitted.addLast(entry);
            if (outstandingSnapshot.get() == 0) {
                quiescenceGate.set(new CompletionGate<>());
            }
            outstandingSnapshot.incrementAndGet();
            completion.complete(new OwnedWorkToken(partition, entry));
            publish();
        });
        return completion.minimalCompletionStage();
    }

    private boolean isEnded(SourcePartitionKey partition) {
        return endedGenerationWatermarks.getOrDefault(identity(partition), -1)
            >= partition.sourceGeneration();
    }

    private static PartitionIdentity identity(SourcePartitionKey partition) {
        return new PartitionIdentity(partition.sourceId(), partition.partition());
    }

    /**
     * Advances only partitions that have no admitted work. Active work therefore freezes its
     * partition's contribution to the global minimum.
     */
    public void advanceIdlePartitions(@NonNull Instant replayClock) {
        executeOnOwner(() -> {
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

    /**
     * Completes the next time all admitted work has settled. Work admitted before that point joins
     * the same interval; work admitted after quiescence starts a new interval.
     */
    public CompletionStage<Void> whenQuiescent() {
        return quiescenceGate.get().stage();
    }

    public Snapshot currentSnapshot() {
        return snapshot.get();
    }

    private void executeOnOwner(Runnable command) {
        ownerExecutor.execute(ownerThreadGuard.guard(command));
    }

    private void publish() {
        ownerThreadGuard.requireOwnerThread();
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

    private static CompletionGate<Void> completedGate() {
        var gate = new CompletionGate<Void>();
        gate.complete(null);
        return gate;
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
        public CompletionStage<Void> settled() {
            return entry.completion.stage();
        }

        @Override
        public void close() {
            if (closeRequested.compareAndSet(false, true)) {
                executeOnOwner(this::settle);
            }
        }

        private void settle() {
            ownerThreadGuard.requireOwnerThread();
            var progress = partitions.get(partition);
            if (progress == null) {
                throw new IllegalStateException(
                    "source partition was retired before work settled: "
                        + partition
                        + "; work="
                        + entry.workId
                );
            }
            if (entry.settled) {
                return;
            }
            entry.settled = true;
            entry.completion.complete(null);
            if (outstandingSnapshot.decrementAndGet() == 0) {
                quiescenceGate.get().complete(null);
            }
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
