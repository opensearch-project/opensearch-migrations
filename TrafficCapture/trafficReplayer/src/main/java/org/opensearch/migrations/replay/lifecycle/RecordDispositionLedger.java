package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

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
    private final Map<SourcePartitionKey, Boolean> generationRunway = new LinkedHashMap<>();

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
        var completion = new CompletableFuture<Void>();
        ownerExecutor.execute(() -> {
            if (unresolved.containsKey(handle.id())
                || pending.containsKey(handle.id())
                || resolved.containsKey(handle.id())) {
                completion.completeExceptionally(
                    new IllegalStateException("record obligation already exists for " + handle.id())
                );
                return;
            }
            generationRunway.putIfAbsent(handle.sourcePartition(), true);
            unresolved.put(handle.id(), new Obligation(handle, owner));
            completion.complete(null);
        });
        return completion.minimalCompletionStage();
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
            completion.completeExceptionally(e);
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
            completion.completeExceptionally(e);
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
            completion.completeExceptionally(e);
            return;
        }
        commitStage.whenComplete((ignored, failure) ->
            ownerExecutor.execute(() -> completeCommit(id, result, failure, completion))
        );
    }

    private void completeCommit(
        RecordId id,
        DispositionResult result,
        Throwable failure,
        CompletableFuture<DispositionResult> completion
    ) {
        if (failure == null) {
            resolve(id, result);
            completion.complete(result);
        } else {
            completion.completeExceptionally(failure);
        }
    }

    public CompletionStage<Map<RecordId, String>> unresolvedObligations() {
        var completion = new CompletableFuture<Map<RecordId, String>>();
        ownerExecutor.execute(() -> {
            var snapshot = new LinkedHashMap<RecordId, String>();
            unresolved.forEach((id, obligation) -> snapshot.put(id, obligation.owner()));
            pending.forEach((id, disposition) -> snapshot.put(id, disposition.obligation().owner()));
            completion.complete(Map.copyOf(snapshot));
        });
        return completion.minimalCompletionStage();
    }

    private <T> Obligation requireOwnedObligation(
        RecordId id,
        String expectedOwner,
        CompletableFuture<T> completion
    ) {
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
    }
}
