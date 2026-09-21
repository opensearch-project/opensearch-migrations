package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceControlRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TrafficStreamRecordId;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class RecordDispositionLedger implements SourcePartitionLifecycleListener {
    public sealed interface Input permits
        PartitionsAssigned,
        PartitionsRevoked,
        PartitionsRetired,
        RegistrationRequested,
        RegistrationSealRequested,
        TransferRequested,
        DispositionRequested,
        UnresolvedSnapshotRequested,
        StateSnapshotRequested {}

    private record PartitionsAssigned(
        @NonNull java.util.List<PartitionGenerationId> partitions
    ) implements Input {}

    private record PartitionsRevoked(
        @NonNull java.util.List<PartitionGenerationId> partitions
    ) implements Input {}

    private record PartitionsRetired(
        @NonNull java.util.List<PartitionGenerationId> partitions
    ) implements Input {}

    private record RegistrationRequested(
        @NonNull RecordHandle handle,
        @NonNull PartitionGenerationId sourcePartition,
        @NonNull String owner,
        @NonNull CompletableFuture<Void> completion
    ) implements Input {}

    private record RegistrationSealRequested(
        @NonNull CompletableFuture<Void> completion
    ) implements Input {}

    private record TransferRequested(
        @NonNull RecordId id,
        @NonNull String expectedOwner,
        @NonNull String newOwner,
        @NonNull CompletableFuture<Void> completion
    ) implements Input {}

    private record DispositionRequested(
        @NonNull RecordId id,
        @NonNull String owner,
        @NonNull RecordDisposition disposition,
        @NonNull CompletableFuture<DispositionResult> completion
    ) implements Input {}

    private record UnresolvedSnapshotRequested(
        @NonNull CompletableFuture<Map<RecordId, String>> completion
    ) implements Input {}

    private record StateSnapshotRequested(
        @NonNull CompletableFuture<StateSnapshot> completion
    ) implements Input {}

    public interface RecordHandle {
        RecordId id();

        SourcePartitionKey sourcePartition();

        void closeContext();

        void releaseWithoutCommit();

        CompletionStage<Void> commit();
    }

    @Value
    @Accessors(fluent = true)
    public static class DispositionResult {
        @NonNull RecordId recordId;
        @NonNull String owner;
        @NonNull RecordDisposition disposition;
    }

    @Value
    @Accessors(fluent = true)
    public static class StateSnapshot {
        int unresolved;
        int pending;
        long resolved;
        int resolvedIndexEntries;
        int failed;
        int runwayGenerations;
        int retiringGenerations;
        int retiredPartitionWatermarks;
    }

    private record SourcePartitionIdentity(@NonNull String topic, int partition) {
        private static SourcePartitionIdentity from(PartitionGenerationId partition) {
            return new SourcePartitionIdentity(
                partition.topicPartition().topic(),
                partition.topicPartition().partition()
            );
        }
    }

    private static final class Obligation {
        private final RecordHandle handle;
        private final String owner;
        private final PartitionGenerationId sourcePartition;

        private Obligation(RecordHandle handle, String owner, PartitionGenerationId sourcePartition) {
            this.handle = handle;
            this.owner = owner;
            this.sourcePartition = sourcePartition;
        }

        private RecordHandle handle() {
            return handle;
        }

        private String owner() {
            return owner;
        }

        private PartitionGenerationId sourcePartition() {
            return sourcePartition;
        }
    }

    private static final class PendingDisposition {
        private final Obligation obligation;

        private PendingDisposition(Obligation obligation) {
            this.obligation = obligation;
        }

        private Obligation obligation() {
            return obligation;
        }
    }

    private final Consumer<Input> ownerInputSink;
    private final OwnerThreadGuard ownerThreadGuard =
        new OwnerThreadGuard("record disposition ledger");
    private final Map<RecordId, Obligation> unresolved = new LinkedHashMap<>();
    private final Map<RecordId, PendingDisposition> pending = new LinkedHashMap<>();
    private final ResolvedRecordIndex resolved = new ResolvedRecordIndex();
    private final Map<RecordId, FailedDisposition> failed = new LinkedHashMap<>();

    private static final class FailedDisposition {
        private final Obligation obligation;
        private final Throwable failure;

        private FailedDisposition(Obligation obligation, Throwable failure) {
            this.obligation = obligation;
            this.failure = failure;
        }
    }
    private final Map<PartitionGenerationId, Boolean> generationRunway = new LinkedHashMap<>();
    private final Set<PartitionGenerationId> retiringGenerations = new java.util.LinkedHashSet<>();
    private final Map<SourcePartitionIdentity, Long> retiredGenerationWatermarks = new LinkedHashMap<>();
    private final AtomicReference<CompletionGate<Void>> quiescenceGate =
        new AtomicReference<>(completedGate());
    private final AtomicBoolean registrationsSealed = new AtomicBoolean();
    private Throwable quiescenceIntervalFailure;

    public RecordDispositionLedger(@NonNull Executor ownerExecutor) {
        this.ownerInputSink = input -> ownerExecutor.execute(() -> apply(input));
    }

    public RecordDispositionLedger(@NonNull Consumer<Input> ownerInputSink) {
        this.ownerInputSink = ownerInputSink;
    }

    @Override
    public void onAssigned(@NonNull Collection<SourcePartitionKey> partitions) {
        ownerInputSink.accept(new PartitionsAssigned(
            partitions.stream().map(SourcePartitionKey::partitionGenerationId).toList()
        ));
    }

    @Override
    public void onRevoked(@NonNull Collection<SourcePartitionKey> partitions) {
        ownerInputSink.accept(new PartitionsRevoked(
            partitions.stream().map(SourcePartitionKey::partitionGenerationId).toList()
        ));
    }

    @Override
    public void onRetired(@NonNull Collection<SourcePartitionKey> partitions) {
        ownerInputSink.accept(new PartitionsRetired(
            partitions.stream().map(SourcePartitionKey::partitionGenerationId).toList()
        ));
    }

    public CompletionStage<Void> register(@NonNull RecordHandle handle, @NonNull String owner) {
        if (registrationsSealed.get()) {
            return rejectedRegistration(handle.id());
        }
        var completion = new CompletableFuture<Void>();
        ownerInputSink.accept(new RegistrationRequested(
            handle,
            handle.sourcePartition().partitionGenerationId(),
            owner,
            completion
        ));
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> sealRegistrations() {
        if (registrationsSealed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        var completion = new CompletableFuture<Void>();
        ownerInputSink.accept(new RegistrationSealRequested(completion));
        return completion.minimalCompletionStage();
    }

    private static CompletionStage<Void> rejectedRegistration(RecordId id) {
        return CompletableFuture.failedFuture(registrationSealedException(id));
    }

    private static void completeRejectedRegistration(CompletableFuture<Void> completion, RecordId id) {
        completion.completeExceptionally(registrationSealedException(id));
    }

    private static IllegalStateException registrationSealedException(RecordId id) {
        return new IllegalStateException("record registration is sealed: " + id);
    }

    public CompletionStage<Void> transfer(
        @NonNull RecordId id,
        @NonNull String expectedOwner,
        @NonNull String newOwner
    ) {
        var completion = new CompletableFuture<Void>();
        ownerInputSink.accept(new TransferRequested(id, expectedOwner, newOwner, completion));
        return completion.minimalCompletionStage();
    }

    public CompletionStage<DispositionResult> dispose(
        @NonNull RecordId id,
        @NonNull String owner,
        @NonNull RecordDisposition disposition
    ) {
        var completion = new CompletableFuture<DispositionResult>();
        ownerInputSink.accept(new DispositionRequested(id, owner, disposition, completion));
        return completion.minimalCompletionStage();
    }

    private void disposeOnOwner(
        RecordId id,
        String owner,
        RecordDisposition disposition,
        CompletableFuture<DispositionResult> completion
    ) {
        ownerThreadGuard.requireOwnerThread();
        var obligation = requireOwnedObligation(id, owner, completion);
        if (obligation == null) {
            return;
        }

        unresolved.remove(id);
        var acceptedDisposition = acceptDisposition(obligation, disposition);
        var result = new DispositionResult(
            id,
            owner,
            acceptedDisposition
        );
        pending.put(id, new PendingDisposition(obligation));
        try {
            obligation.handle().closeContext();
        } catch (Exception e) {
            resolveExceptionally(id, obligation, e, completion);
            return;
        }

        if (acceptedDisposition instanceof RecordDisposition.Commit) {
            commit(id, obligation, result, completion);
        } else {
            releaseWithoutCommit(id, obligation, result, completion);
        }
    }

    private void releaseWithoutCommit(
        RecordId id,
        Obligation obligation,
        DispositionResult result,
        CompletableFuture<DispositionResult> completion
    ) {
        try {
            obligation.handle().releaseWithoutCommit();
            resolve(id);
            completion.complete(result);
        } catch (Exception e) {
            resolveExceptionally(id, obligation, e, completion);
        }
    }

    private RecordDisposition acceptDisposition(Obligation obligation, RecordDisposition requested) {
        if (!(requested instanceof RecordDisposition.Commit)) {
            return requested;
        }
        if (!generationRunway.getOrDefault(obligation.sourcePartition(), false)) {
            return new RecordDisposition.Retain(
                "source-runway-lost-before-" + requested.reasonCode()
            );
        }
        return requested;
    }

    private void commit(
        RecordId id,
        Obligation obligation,
        DispositionResult result,
        CompletableFuture<DispositionResult> completion
    ) {
        final CompletionStage<Void> commitStage;
        try {
            commitStage = obligation.handle().commit();
        } catch (Exception e) {
            resolveExceptionally(id, obligation, e, completion);
            return;
        }
        resolve(id);
        completion.complete(result);
        commitStage.whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.atWarn()
                    .setCause(unwrap(failure))
                    .setMessage(
                        "Kafka commit operation completed exceptionally after local record cleanup; "
                            + "record={}, partition={}"
                    )
                    .addArgument(id)
                    .addArgument(obligation::sourcePartition)
                    .log();
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public CompletionStage<Map<RecordId, String>> unresolvedObligations() {
        var completion = new CompletableFuture<Map<RecordId, String>>();
        ownerInputSink.accept(new UnresolvedSnapshotRequested(completion));
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> whenQuiescent() {
        return quiescenceGate.get().stage();
    }

    public CompletionStage<StateSnapshot> stateSnapshot() {
        var completion = new CompletableFuture<StateSnapshot>();
        ownerInputSink.accept(new StateSnapshotRequested(completion));
        return completion.minimalCompletionStage();
    }

    void apply(@NonNull Input input) {
        ownerThreadGuard.guard(() -> {
            switch (input) {
                case PartitionsAssigned assigned -> applyAssigned(assigned);
                case PartitionsRevoked revoked -> applyRevoked(revoked);
                case PartitionsRetired retired -> applyRetired(retired);
                case RegistrationRequested registration -> applyRegistration(registration);
                case RegistrationSealRequested seal -> applyRegistrationSeal(seal);
                case TransferRequested transfer -> applyTransfer(transfer);
                case DispositionRequested disposition -> disposeOnOwner(
                    disposition.id(),
                    disposition.owner(),
                    disposition.disposition(),
                    disposition.completion()
                );
                case UnresolvedSnapshotRequested snapshot -> applyUnresolvedSnapshot(snapshot);
                case StateSnapshotRequested snapshot -> snapshot.completion().complete(snapshotOnOwner());
            }
        }).run();
    }

    private void applyAssigned(PartitionsAssigned assigned) {
        assigned.partitions().forEach(partition -> {
            if (isRetired(partition)) {
                throw new IllegalStateException("source assigned an already-retired generation: " + partition);
            }
            generationRunway.put(partition, true);
        });
    }

    private void applyRevoked(PartitionsRevoked revoked) {
        revoked.partitions().forEach(partition -> generationRunway.put(partition, false));
    }

    private void applyRetired(PartitionsRetired retired) {
        retired.partitions().forEach(this::retireGeneration);
    }

    private void applyRegistration(RegistrationRequested registration) {
        var handle = registration.handle();
        var completion = registration.completion();
        if (registrationsSealed.get()) {
            completeRejectedRegistration(completion, handle.id());
            return;
        }
        var sourcePartition = registration.sourcePartition();
        var identityFailure = validateIdentity(handle.id(), sourcePartition);
        if (identityFailure != null) {
            completion.completeExceptionally(identityFailure);
            return;
        }
        if (isRetired(sourcePartition)) {
            var failure = retiredRegistrationException(handle.id(), sourcePartition);
            log.atError()
                .setCause(failure)
                .setMessage("Rejected record registration after source generation retirement; state={}")
                .addArgument(this::snapshotOnOwner)
                .log();
            completion.completeExceptionally(failure);
            return;
        }
        if (unresolved.containsKey(handle.id())
            || pending.containsKey(handle.id())
            || resolved.contains(handle.id())
            || failed.containsKey(handle.id())) {
            completion.completeExceptionally(
                new IllegalStateException("record obligation already exists for " + handle.id())
            );
            return;
        }
        if (unresolved.isEmpty() && pending.isEmpty()) {
            quiescenceGate.set(new CompletionGate<>());
            quiescenceIntervalFailure = null;
        }
        generationRunway.putIfAbsent(sourcePartition, true);
        unresolved.put(
            handle.id(),
            new Obligation(handle, registration.owner(), sourcePartition)
        );
        completion.complete(null);
    }

    private void applyRegistrationSeal(RegistrationSealRequested seal) {
        registrationsSealed.set(true);
        seal.completion().complete(null);
    }

    private void applyTransfer(TransferRequested transfer) {
        var obligation = requireOwnedObligation(
            transfer.id(),
            transfer.expectedOwner(),
            transfer.completion()
        );
        if (obligation != null) {
            unresolved.put(
                transfer.id(),
                new Obligation(obligation.handle(), transfer.newOwner(), obligation.sourcePartition())
            );
            transfer.completion().complete(null);
        }
    }

    private void applyUnresolvedSnapshot(UnresolvedSnapshotRequested requested) {
        var snapshot = new LinkedHashMap<RecordId, String>();
        unresolved.forEach((id, obligation) -> snapshot.put(id, obligation.owner()));
        pending.forEach((id, disposition) -> snapshot.put(id, disposition.obligation().owner()));
        failed.forEach((id, disposition) -> snapshot.put(id, disposition.obligation.owner()));
        requested.completion().complete(Map.copyOf(snapshot));
    }

    private <T> Obligation requireOwnedObligation(
        RecordId id,
        String expectedOwner,
        CompletableFuture<T> completion
    ) {
        var failedDisposition = failed.get(id);
        if (failedDisposition != null) {
            completion.completeExceptionally(
                new IllegalStateException(
                    "record disposition already failed terminally: " + id,
                    failedDisposition.failure
                )
            );
            return null;
        }
        if (pending.containsKey(id)) {
            completion.completeExceptionally(
                new IllegalStateException("record disposition is already in progress: " + id)
            );
            return null;
        }
        if (resolved.contains(id)) {
            completion.completeExceptionally(new IllegalStateException("record was already disposed: " + id));
            return null;
        }
        var obligation = unresolved.get(id);
        if (obligation == null) {
            completion.completeExceptionally(new IllegalStateException("unknown record obligation: " + id));
            return null;
        }
        if (!obligation.owner().equals(expectedOwner)) {
            completion.completeExceptionally(
                new IllegalStateException(
                    "record " + id + " is owned by " + obligation.owner() + ", not " + expectedOwner
                )
            );
            return null;
        }
        return obligation;
    }

    private void resolve(RecordId id) {
        ownerThreadGuard.requireOwnerThread();
        var obligation = pending.get(id).obligation();
        pending.remove(id);
        resolved.add(id, obligation.sourcePartition());
        tryRetireGeneration(obligation.sourcePartition());
        maybeCompleteQuiescence();
    }

    /**
     * A synchronous disposition failure still settles its obligation for quiescence purposes.
     * Leaving it pending would hold the quiescence gate open forever. A Kafka commit result that
     * arrives after {@link RecordHandle#commit()} returns is diagnostic only and never reaches
     * this path.
     */
    private void resolveExceptionally(
        RecordId id,
        Obligation obligation,
        Throwable failure,
        CompletableFuture<?> completion
    ) {
        ownerThreadGuard.requireOwnerThread();
        pending.remove(id);
        failed.put(id, new FailedDisposition(obligation, failure));
        if (quiescenceIntervalFailure == null) {
            quiescenceIntervalFailure = failure;
        } else if (quiescenceIntervalFailure != failure) {
            quiescenceIntervalFailure.addSuppressed(failure);
        }
        completion.completeExceptionally(failure);
        maybeCompleteQuiescence();
    }

    private void retireGeneration(PartitionGenerationId partition) {
        ownerThreadGuard.requireOwnerThread();
        retiredGenerationWatermarks.merge(
            SourcePartitionIdentity.from(partition),
            partition.localSequence(),
            Math::max
        );
        generationRunway.remove(partition);
        retiringGenerations.add(partition);
        tryRetireGeneration(partition);
    }

    private void tryRetireGeneration(PartitionGenerationId partition) {
        if (!retiringGenerations.contains(partition)
            || hasObligationFor(unresolved, partition)
            || hasPendingDispositionFor(partition)
            || hasFailedDispositionFor(partition)) {
            return;
        }
        var removedResolved = resolved.retire(partition);
        retiringGenerations.remove(partition);
        log.atDebug()
            .setMessage("Retired source generation {}; removedResolvedHistory={}; state={}")
            .addArgument(partition)
            .addArgument(removedResolved)
            .addArgument(this::snapshotOnOwner)
            .log();
    }

    private static boolean hasObligationFor(
        Map<RecordId, Obligation> obligations,
        PartitionGenerationId partition
    ) {
        return obligations.values().stream().anyMatch(obligation ->
            obligation.sourcePartition().equals(partition)
        );
    }

    private boolean hasPendingDispositionFor(PartitionGenerationId partition) {
        return pending.values().stream().anyMatch(disposition ->
            disposition.obligation().sourcePartition().equals(partition)
        );
    }

    private boolean hasFailedDispositionFor(PartitionGenerationId partition) {
        return failed.values().stream().anyMatch(disposition ->
            disposition.obligation.sourcePartition().equals(partition)
        );
    }

    private boolean isRetired(PartitionGenerationId partition) {
        return partition.localSequence() <= retiredGenerationWatermarks.getOrDefault(
            SourcePartitionIdentity.from(partition),
            -1L
        );
    }

    private StateSnapshot snapshotOnOwner() {
        ownerThreadGuard.requireOwnerThread();
        return new StateSnapshot(
            unresolved.size(),
            pending.size(),
            resolved.size(),
            resolved.indexEntries(),
            failed.size(),
            generationRunway.size(),
            retiringGenerations.size(),
            retiredGenerationWatermarks.size()
        );
    }

    private IllegalStateException retiredRegistrationException(
        RecordId recordId,
        PartitionGenerationId partition
    ) {
        return new IllegalStateException(
            "record registration arrived after source generation retirement: record="
                + recordId
                + ", partition="
                + partition
                + ", state="
                + snapshotOnOwner()
        );
    }

    private static IllegalArgumentException validateIdentity(
        RecordId recordId,
        PartitionGenerationId partition
    ) {
        if (recordId instanceof KafkaRecordId kafka
            && (!kafka.topic().equals(partition.topicPartition().topic())
                || kafka.partition() != partition.topicPartition().partition()
                || kafka.sourceGeneration() != partition.localSequence())) {
            return new IllegalArgumentException(
                "Kafka record identity does not match its source partition: record="
                    + recordId
                    + ", partition="
                    + partition
            );
        }
        int recordGeneration;
        if (recordId instanceof TrafficStreamRecordId trafficStream) {
            recordGeneration = trafficStream.sourceGeneration();
        } else if (recordId instanceof SourceControlRecordId sourceControl) {
            recordGeneration = sourceControl.sourceGeneration();
        } else {
            return null;
        }
        if (recordGeneration != partition.localSequence()) {
            return new IllegalArgumentException(
                "record generation does not match its source partition: record="
                    + recordId
                    + ", partition="
                    + partition
            );
        }
        return null;
    }

    private void maybeCompleteQuiescence() {
        ownerThreadGuard.requireOwnerThread();
        if (unresolved.isEmpty() && pending.isEmpty()) {
            if (quiescenceIntervalFailure == null) {
                quiescenceGate.get().complete(null);
            } else {
                quiescenceGate.get().completeExceptionally(quiescenceIntervalFailure);
            }
        }
    }

    private static CompletionGate<Void> completedGate() {
        var gate = new CompletionGate<Void>();
        gate.complete(null);
        return gate;
    }
}
