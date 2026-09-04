package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.lifecycle.ReplayDispositionPolicy.Decision;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

public final class ReplayTransaction<R> {
    private static final String ALREADY_TERMINATED = "transaction already terminated for ";

    public interface EvidenceWriter<R> {
        CompletionStage<EvidenceOutcome> write(
            ReplayRequestId requestId,
            SourceOutcome sourceOutcome,
            TargetOutcome<R> targetOutcome
        );
    }

    @Value
    @Accessors(fluent = true)
    public static class TransactionOutcome {
        @NonNull ReplayRequestId requestId;
        @NonNull SourceOutcome sourceOutcome;
        @NonNull TargetOutcome<?> targetOutcome;
        @NonNull EvidenceOutcome evidenceOutcome;
        @NonNull RecordDisposition disposition;
        boolean haltReplay;
    }

    private enum State {
        WAITING_FOR_JOIN,
        WRITING_EVIDENCE,
        DISPOSING,
        TERMINATED
    }

    private final ReplayRequestId requestId;
    private final String ledgerOwner;
    private final ActorMailbox mailbox;
    private final EvidenceWriter<R> evidenceWriter;
    private final ReplayDispositionPolicy dispositionPolicy;
    private final RecordDispositionLedger dispositionLedger;
    private final List<RecordId> recordIds;
    private final Deque<AutoCloseable> ownedResources = new ArrayDeque<>();
    private final CompletionGate<TransactionOutcome> completion = new CompletionGate<>();
    private SourceOutcome sourceOutcome;
    private TargetOutcome<R> targetOutcome;
    private EvidenceOutcome evidenceOutcome;
    private State state = State.WAITING_FOR_JOIN;
    private boolean resourcesReleased;

    public ReplayTransaction(
        @NonNull ReplayRequestId requestId,
        @NonNull ActorMailbox mailbox,
        @NonNull EvidenceWriter<R> evidenceWriter,
        @NonNull ReplayDispositionPolicy dispositionPolicy,
        @NonNull RecordDispositionLedger dispositionLedger,
        @NonNull Collection<? extends RecordId> recordIds,
        @NonNull Collection<? extends AutoCloseable> resources
    ) {
        this.requestId = requestId;
        this.ledgerOwner = requestId.toString();
        this.mailbox = mailbox;
        this.evidenceWriter = evidenceWriter;
        this.dispositionPolicy = dispositionPolicy;
        this.dispositionLedger = dispositionLedger;
        this.recordIds = new ArrayList<>(recordIds);
        resources.forEach(ownedResources::addLast);
    }

    public String ledgerOwner() {
        return ledgerOwner;
    }

    public CompletionStage<Void> settleSource(@NonNull SourceOutcome outcome) {
        return settleSource(outcome, List.of());
    }

    public CompletionStage<Void> settleSource(
        @NonNull SourceOutcome outcome,
        @NonNull Collection<? extends RecordId> additionalRecordIds
    ) {
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (state == State.TERMINATED) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException(ALREADY_TERMINATED + requestId)
                );
                return;
            }
            if (sourceOutcome != null) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException("source outcome already settled for " + requestId)
                );
                return;
            }
            for (var recordId : additionalRecordIds) {
                if (recordIds.contains(recordId)) {
                    acknowledgement.completeExceptionally(
                        new IllegalStateException("record already belongs to " + requestId + ": " + recordId)
                    );
                    return;
                }
                recordIds.add(recordId);
            }
            sourceOutcome = outcome;
            acknowledgement.complete(null);
            tryAdvance();
        });
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<Void> settleTarget(@NonNull TargetOutcome<R> outcome) {
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (state == State.TERMINATED) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException(ALREADY_TERMINATED + requestId)
                );
                return;
            }
            if (targetOutcome != null) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException("target outcome already settled for " + requestId)
                );
                return;
            }
            targetOutcome = outcome;
            acknowledgement.complete(null);
            tryAdvance();
        });
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<TransactionOutcome> completion() {
        return completion.stage();
    }

    public CompletionStage<Void> fail(@NonNull Throwable cause) {
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (state == State.TERMINATED) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException(ALREADY_TERMINATED + requestId)
                );
                return;
            }
            state = State.TERMINATED;
            var releaseFailure = releaseResources();
            if (releaseFailure != null) {
                cause.addSuppressed(releaseFailure);
            }
            completion.completeExceptionally(cause);
            acknowledgement.complete(null);
        });
        return acknowledgement.minimalCompletionStage();
    }

    private void tryAdvance() {
        assertInMailbox();
        if (state != State.WAITING_FOR_JOIN || sourceOutcome == null || targetOutcome == null) {
            return;
        }
        if (!dispositionPolicy.requiresEvidence(sourceOutcome, targetOutcome)) {
            evidenceOutcome = new EvidenceOutcome.NotRequired("teardown");
            beginDisposition();
            return;
        }

        state = State.WRITING_EVIDENCE;
        CompletionStage<EvidenceOutcome> evidenceStage;
        try {
            evidenceStage = evidenceWriter.write(requestId, sourceOutcome, targetOutcome);
        } catch (Exception t) {
            evidenceStage = CompletableFuture.completedFuture(new EvidenceOutcome.Failed(t));
        }
        evidenceStage.whenComplete((outcome, failure) ->
            mailbox.execute(() -> {
                if (state != State.WRITING_EVIDENCE) {
                    return;
                }
                evidenceOutcome = failure == null
                    ? outcome
                    : new EvidenceOutcome.Failed(unwrap(failure));
                if (evidenceOutcome == null) {
                    evidenceOutcome = new EvidenceOutcome.Failed(
                        new NullPointerException("evidence writer completed without an outcome")
                    );
                }
                beginDisposition();
            })
        );
    }

    private void beginDisposition() {
        assertInMailbox();
        state = State.DISPOSING;
        var decision = dispositionPolicy.decide(sourceOutcome, targetOutcome, evidenceOutcome);
        var dispositionStages = new ArrayList<CompletableFuture<RecordDispositionLedger.DispositionResult>>();
        for (var recordId : recordIds) {
            dispositionStages.add(
                dispositionLedger.dispose(recordId, ledgerOwner, decision.disposition()).toCompletableFuture()
            );
        }
        CompletableFuture.allOf(dispositionStages.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) -> mailbox.execute(() -> {
                var dispositionFailure = failure == null ? null : unwrap(failure);
                var acceptedDisposition = dispositionFailure == null
                    ? acceptedDisposition(decision.disposition(), dispositionStages)
                    : decision.disposition();
                finish(decision, acceptedDisposition, dispositionFailure);
            }));
    }

    private RecordDisposition acceptedDisposition(
        RecordDisposition requestedDisposition,
        List<CompletableFuture<RecordDispositionLedger.DispositionResult>> dispositionStages
    ) {
        return dispositionStages.stream()
            .map(CompletableFuture::join)
            .map(RecordDispositionLedger.DispositionResult::disposition)
            .filter(RecordDisposition.Retain.class::isInstance)
            .findFirst()
            .orElse(requestedDisposition);
    }

    private void finish(
        Decision decision,
        RecordDisposition acceptedDisposition,
        Throwable dispositionFailure
    ) {
        assertInMailbox();
        if (state == State.TERMINATED) {
            return;
        }
        Throwable releaseFailure = releaseResources();
        state = State.TERMINATED;
        if (dispositionFailure != null) {
            if (releaseFailure != null) {
                dispositionFailure.addSuppressed(releaseFailure);
            }
            completion.completeExceptionally(dispositionFailure);
            return;
        }
        if (releaseFailure != null) {
            completion.completeExceptionally(releaseFailure);
            return;
        }
        completion.complete(
            new TransactionOutcome(
                requestId,
                sourceOutcome,
                targetOutcome,
                evidenceOutcome,
                acceptedDisposition,
                decision.haltReplay()
            )
        );
    }

    private Throwable releaseResources() {
        if (resourcesReleased) {
            return null;
        }
        resourcesReleased = true;
        Throwable firstFailure = null;
        while (!ownedResources.isEmpty()) {
            try {
                ownedResources.removeLast().close();
            } catch (Exception t) {
                if (firstFailure == null) {
                    firstFailure = t;
                } else {
                    firstFailure.addSuppressed(t);
                }
            }
        }
        return firstFailure;
    }

    private void assertInMailbox() {
        if (!mailbox.inMailbox()) {
            throw new IllegalStateException("replay transaction transition ran outside its mailbox");
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        var current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null)
        {
            current = current.getCause();
        }
        return current;
    }
}
