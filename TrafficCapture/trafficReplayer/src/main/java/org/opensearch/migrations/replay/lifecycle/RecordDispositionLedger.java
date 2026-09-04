package org.opensearch.migrations.replay.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;

import lombok.NonNull;

public final class RecordDispositionLedger {
    public interface RecordHandle {
        KafkaRecordId id();

        void closeContext();

        CompletionStage<Void> commit();
    }

    public record DispositionResult(
        @NonNull KafkaRecordId recordId,
        @NonNull String owner,
        @NonNull RecordDisposition disposition
    ) {}

    private record Obligation(RecordHandle handle, String owner) {}

    private final Executor ownerExecutor;
    private final Map<KafkaRecordId, Obligation> unresolved = new LinkedHashMap<>();
    private final Map<KafkaRecordId, DispositionResult> resolved = new LinkedHashMap<>();

    public RecordDispositionLedger(@NonNull Executor ownerExecutor) {
        this.ownerExecutor = ownerExecutor;
    }

    public CompletionStage<Void> register(@NonNull RecordHandle handle, @NonNull String owner) {
        var completion = new CompletableFuture<Void>();
        ownerExecutor.execute(() -> {
            if (unresolved.containsKey(handle.id()) || resolved.containsKey(handle.id())) {
                completion.completeExceptionally(
                    new IllegalStateException("record obligation already exists for " + handle.id())
                );
                return;
            }
            unresolved.put(handle.id(), new Obligation(handle, owner));
            completion.complete(null);
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> transfer(
        @NonNull KafkaRecordId id,
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
        @NonNull KafkaRecordId id,
        @NonNull String owner,
        @NonNull RecordDisposition disposition
    ) {
        var completion = new CompletableFuture<DispositionResult>();
        ownerExecutor.execute(() -> {
            var obligation = requireOwnedObligation(id, owner, completion);
            if (obligation == null) {
                return;
            }

            unresolved.remove(id);
            var result = new DispositionResult(id, owner, disposition);
            resolved.put(id, result);
            try {
                obligation.handle().closeContext();
            } catch (Throwable t) {
                completion.completeExceptionally(t);
                return;
            }

            if (disposition instanceof RecordDisposition.Commit) {
                obligation.handle().commit().whenComplete((ignored, failure) ->
                    ownerExecutor.execute(() -> {
                        if (failure == null) {
                            completion.complete(result);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    })
                );
            } else {
                completion.complete(result);
            }
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Map<KafkaRecordId, String>> unresolvedObligations() {
        var completion = new CompletableFuture<Map<KafkaRecordId, String>>();
        ownerExecutor.execute(() -> {
            var snapshot = new LinkedHashMap<KafkaRecordId, String>();
            unresolved.forEach((id, obligation) -> snapshot.put(id, obligation.owner()));
            completion.complete(Map.copyOf(snapshot));
        });
        return completion.minimalCompletionStage();
    }

    private <T> Obligation requireOwnedObligation(
        KafkaRecordId id,
        String expectedOwner,
        CompletableFuture<T> completion
    ) {
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
}
