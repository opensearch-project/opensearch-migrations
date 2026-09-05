package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

public final class RecordDispositionLedger implements SourcePartitionLifecycleListener {
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

    private static final class Obligation {
        private final RecordHandle handle;
        private final String owner;

        private Obligation(RecordHandle handle, String owner) {
            this.handle = handle;
            this.owner = owner;
        }

        private RecordHandle handle() {
            return handle;
        }

        private String owner() {
            return owner;
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

    private final Executor ownerExecutor;
    private final Map<RecordId, Obligation> unresolved = new LinkedHashMap<>();
    private final Map<RecordId, PendingDisposition> pending = new LinkedHashMap<>();
    private final Map<RecordId, DispositionResult> resolved = new LinkedHashMap<>();
    private final Map<RecordId, FailedDisposition> failed = new LinkedHashMap<>();

    private static final class FailedDisposition {
        private final Obligation obligation;
        private final Throwable failure;

        private FailedDisposition(Obligation obligation, Throwable failure) {
            this.obligation = obligation;
            this.failure = failure;
        }
    }
    private final Map<SourcePartitionKey, Boolean> generationRunway = new LinkedHashMap<>();
    private final AtomicReference<CompletionGate<Void>> quiescenceGate =
        new AtomicReference<>(completedGate());
    private final AtomicBoolean registrationsSealed = new AtomicBoolean();
    private Throwable quiescenceIntervalFailure;

    public RecordDispositionLedger(@NonNull Executor ownerExecutor) {
        this.ownerExecutor = ownerExecutor;
    }

    @Override
    public void onAssigned(@NonNull Collection<SourcePartitionKey> partitions) {
        ownerExecutor.execute(() -> partitions.forEach(partition -> generationRunway.put(partition, true)));
    }

    @Override
    public void onRevoked(@NonNull Collection<SourcePartitionKey> partitions) {
        ownerExecutor.execute(() -> partitions.forEach(partition -> generationRunway.put(partition, false)));
    }

    public CompletionStage<Void> register(@NonNull RecordHandle handle, @NonNull String owner) {
        if (registrationsSealed.get()) {
            return rejectedRegistration(handle.id());
        }
        var completion = new CompletableFuture<Void>();
        ownerExecutor.execute(() -> {
            if (registrationsSealed.get()) {
                completeRejectedRegistration(completion, handle.id());
                return;
            }
            if (unresolved.containsKey(handle.id())
                || pending.containsKey(handle.id())
                || resolved.containsKey(handle.id())) {
                completion.completeExceptionally(
                    new IllegalStateException("record obligation already exists for " + handle.id())
                );
                return;
            }
            if (unresolved.isEmpty() && pending.isEmpty()) {
                quiescenceGate.set(new CompletionGate<>());
                quiescenceIntervalFailure = null;
            }
            generationRunway.putIfAbsent(handle.sourcePartition(), true);
            unresolved.put(handle.id(), new Obligation(handle, owner));
            completion.complete(null);
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> sealRegistrations() {
        if (registrationsSealed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        var completion = new CompletableFuture<Void>();
        ownerExecutor.execute(() -> {
            registrationsSealed.set(true);
            completion.complete(null);
        });
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
        ownerExecutor.execute(() -> {
            var obligation = requireOwnedObligation(id, expectedOwner, completion);
            if (obligation != null) {
                unresolved.put(id, new Obligation(obligation.handle(), newOwner));
                completion.complete(null);
            }
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<DispositionResult> dispose(
        @NonNull RecordId id,
        @NonNull String owner,
        @NonNull RecordDisposition disposition
    ) {
        var completion = new CompletableFuture<DispositionResult>();
        ownerExecutor.execute(() -> disposeOnOwner(id, owner, disposition, completion));
        return completion.minimalCompletionStage();
    }

    private void disposeOnOwner(
        RecordId id,
        String owner,
        RecordDisposition disposition,
        CompletableFuture<DispositionResult> completion
    ) {
        var obligation = requireOwnedObligation(id, owner, completion);
        if (obligation == null) {
            return;
        }

        unresolved.remove(id);
        var acceptedDisposition = acceptDisposition(obligation, disposition);
        var result = new DispositionResult(id, owner, acceptedDisposition);
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
            resolve(id, result);
            completion.complete(result);
        } catch (Exception e) {
            resolveExceptionally(id, obligation, e, completion);
        }
    }

    private RecordDisposition acceptDisposition(Obligation obligation, RecordDisposition requested) {
        if (requested instanceof RecordDisposition.Commit
            && !generationRunway.getOrDefault(obligation.handle().sourcePartition(), false)) {
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
        commitStage.whenComplete((ignored, failure) ->
            ownerExecutor.execute(() -> completeCommit(id, obligation, result, failure, completion))
        );
    }

    private void completeCommit(
        RecordId id,
        Obligation obligation,
        DispositionResult result,
        Throwable failure,
        CompletableFuture<DispositionResult> completion
    ) {
        if (failure == null) {
            resolve(id, result);
            completion.complete(result);
            return;
        }
        if (unwrap(failure) instanceof SourceRunwayLostException runwayLost
            && runwayLost.getPartition().equals(obligation.handle().sourcePartition())) {
            var retainedResult = new DispositionResult(
                id,
                result.owner(),
                new RecordDisposition.Retain(
                    "source-runway-lost-after-" + result.disposition().reasonCode()
                )
            );
            resolve(id, retainedResult);
            completion.complete(retainedResult);
            return;
        }
        resolveExceptionally(id, obligation, failure, completion);
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
        ownerExecutor.execute(() -> {
            var snapshot = new LinkedHashMap<RecordId, String>();
            unresolved.forEach((id, obligation) -> snapshot.put(id, obligation.owner()));
            pending.forEach((id, disposition) -> snapshot.put(id, disposition.obligation().owner()));
            failed.forEach((id, disposition) -> snapshot.put(id, disposition.obligation.owner()));
            completion.complete(Map.copyOf(snapshot));
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> whenQuiescent() {
        return quiescenceGate.get().stage();
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
                new IllegalStateException("record disposition is awaiting acknowledgement: " + id)
            );
            return null;
        }
        if (resolved.containsKey(id)) {
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

    private void resolve(RecordId id, DispositionResult result) {
        pending.remove(id);
        resolved.put(id, result);
        maybeCompleteQuiescence();
    }

    /**
     * A failed acknowledgement still settles its obligation for quiescence purposes. Leaving it
     * pending would hold the quiescence gate open forever, deadlocking shutdown behind an
     * acknowledgement that can no longer arrive; instead the failure propagates through both the
     * disposer's completion and, once no work remains, the quiescence gate itself. The record
     * stays visible in {@link #unresolvedObligations()} so shutdown diagnostics can still name it.
     */
    private void resolveExceptionally(
        RecordId id,
        Obligation obligation,
        Throwable failure,
        CompletableFuture<?> completion
    ) {
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

    private void maybeCompleteQuiescence() {
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
