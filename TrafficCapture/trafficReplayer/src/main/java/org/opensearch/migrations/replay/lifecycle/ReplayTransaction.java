package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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

    public enum Phase {
        WAITING_FOR_JOIN("waiting_for_join"),
        WRITING_EVIDENCE("writing_evidence"),
        DISPOSING("disposing");

        private final String metricLabel;

        Phase(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public enum RunwayState {
        AVAILABLE("available"),
        LOST("lost");

        private final String metricLabel;

        RunwayState(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public enum RunwayLossReason {
        SOURCE_REASSIGNMENT("source_reassignment"),
        SHUTDOWN("shutdown");

        private final String metricLabel;

        RunwayLossReason(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public enum TerminalOutcome {
        COMMITTED("committed"),
        RETAINED("retained"),
        FAILED("failed");

        private final String metricLabel;

        TerminalOutcome(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {
            @Override
            public void phaseChanged(Phase phase, int delta) {
                // Metrics are optional for non-production transactions.
            }

            @Override
            public void runwayStateChanged(RunwayState state, int delta) {
                // Metrics are optional for non-production transactions.
            }

            @Override
            public void runwayLost(RunwayLossReason reason) {
                // Metrics are optional for non-production transactions.
            }

            @Override
            public void terminalOutcome(TerminalOutcome outcome) {
                // Metrics are optional for non-production transactions.
            }

            @Override
            public void disposition(RecordDisposition disposition) {
                // Metrics are optional for non-production transactions.
            }
        };

        void phaseChanged(Phase phase, int delta);

        void runwayStateChanged(RunwayState state, int delta);

        void runwayLost(RunwayLossReason reason);

        void terminalOutcome(TerminalOutcome outcome);

        void disposition(RecordDisposition disposition);
    }

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

    private final ReplayRequestId requestId;
    private final String ledgerOwner;
    private final ActorMailbox mailbox;
    private final EvidenceWriter<R> evidenceWriter;
    private final ReplayDispositionPolicy dispositionPolicy;
    private final RecordDispositionLedger dispositionLedger;
    private final List<RecordId> recordIds;
    private final Deque<AutoCloseable> ownedResources = new ArrayDeque<>();
    private final Set<AutoCloseable> ownedResourceIdentities =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final CompletionGate<TransactionOutcome> completion = new CompletionGate<>();
    private final Metrics metrics;
    private SourceOutcome sourceOutcome;
    private TargetOutcome<R> targetOutcome;
    private EvidenceOutcome evidenceOutcome;
    private Phase phase = Phase.WAITING_FOR_JOIN;
    private RunwayState runwayState = RunwayState.AVAILABLE;
    private Throwable pendingFailure;
    private boolean metricsActive;
    private boolean resourcesReleased;
    private boolean terminated;

    public ReplayTransaction(
        @NonNull ReplayRequestId requestId,
        @NonNull ActorMailbox mailbox,
        @NonNull EvidenceWriter<R> evidenceWriter,
        @NonNull ReplayDispositionPolicy dispositionPolicy,
        @NonNull RecordDispositionLedger dispositionLedger,
        @NonNull Collection<? extends RecordId> recordIds,
        @NonNull Collection<? extends AutoCloseable> resources
    ) {
        this(
            requestId,
            mailbox,
            evidenceWriter,
            dispositionPolicy,
            dispositionLedger,
            recordIds,
            resources,
            Metrics.NOOP
        );
    }

    public ReplayTransaction(
        @NonNull ReplayRequestId requestId,
        @NonNull ActorMailbox mailbox,
        @NonNull EvidenceWriter<R> evidenceWriter,
        @NonNull ReplayDispositionPolicy dispositionPolicy,
        @NonNull RecordDispositionLedger dispositionLedger,
        @NonNull Collection<? extends RecordId> recordIds,
        @NonNull Collection<? extends AutoCloseable> resources,
        @NonNull Metrics metrics
    ) {
        this.requestId = requestId;
        this.ledgerOwner = requestId.toString();
        this.mailbox = mailbox;
        this.evidenceWriter = evidenceWriter;
        this.dispositionPolicy = dispositionPolicy;
        this.dispositionLedger = dispositionLedger;
        this.recordIds = new ArrayList<>(recordIds);
        this.metrics = metrics;
        resources.forEach(this::adoptResource);
        mailbox.execute(this::activateMetrics);
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
            if (terminated) {
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
            if (terminated) {
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

    public CompletionStage<Void> ownResource(@NonNull AutoCloseable resource) {
        var acknowledgement = new CompletableFuture<Void>();
        try {
            mailbox.execute(() -> {
                if (terminated) {
                    var closeFailure = closeResource(resource);
                    var failure = new IllegalStateException(ALREADY_TERMINATED + requestId);
                    if (closeFailure != null) {
                        failure.addSuppressed(closeFailure);
                    }
                    acknowledgement.completeExceptionally(failure);
                    return;
                }
                if (!ownedResourceIdentities.add(resource)) {
                    acknowledgement.completeExceptionally(
                        new IllegalStateException("transaction already owns resource for " + requestId)
                    );
                    return;
                }
                ownedResources.addLast(resource);
                acknowledgement.complete(null);
            });
        } catch (Throwable admissionFailure) {
            addSuppressed(admissionFailure, closeResource(resource));
            acknowledgement.completeExceptionally(admissionFailure);
        }
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<TransactionOutcome> completion() {
        return completion.stage();
    }

    public CompletionStage<Void> observeRunwayLost(@NonNull RunwayLossReason reason) {
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (!terminated && runwayState == RunwayState.AVAILABLE) {
                if (metricsActive) {
                    metrics.runwayStateChanged(runwayState, -1);
                }
                runwayState = RunwayState.LOST;
                if (metricsActive) {
                    metrics.runwayStateChanged(runwayState, 1);
                    metrics.runwayLost(reason);
                }
            }
            acknowledgement.complete(null);
        });
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<Void> fail(@NonNull Throwable cause) {
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (terminated) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException(ALREADY_TERMINATED + requestId)
                );
                return;
            }
            if (pendingFailure != null) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException("transaction failure already recorded for " + requestId)
                );
                return;
            }
            if (phase == Phase.DISPOSING) {
                pendingFailure = cause;
            } else {
                completeFailure(cause, null);
            }
            acknowledgement.complete(null);
        });
        return acknowledgement.minimalCompletionStage();
    }

    private void tryAdvance() {
        assertInMailbox();
        if (phase != Phase.WAITING_FOR_JOIN || sourceOutcome == null || targetOutcome == null) {
            return;
        }
        if (!dispositionPolicy.requiresEvidence(sourceOutcome, targetOutcome)) {
            evidenceOutcome = new EvidenceOutcome.NotRequired("teardown");
            beginDisposition();
            return;
        }

        transitionPhase(Phase.WRITING_EVIDENCE);
        CompletionStage<EvidenceOutcome> evidenceStage;
        try {
            evidenceStage = evidenceWriter.write(requestId, sourceOutcome, targetOutcome);
        } catch (Exception t) {
            evidenceStage = CompletableFuture.completedFuture(new EvidenceOutcome.Failed(t));
        }
        evidenceStage.whenComplete((outcome, failure) ->
            mailbox.execute(() -> {
                if (terminated || phase != Phase.WRITING_EVIDENCE) {
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
        transitionPhase(Phase.DISPOSING);
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
        if (terminated) {
            return;
        }
        if (dispositionFailure == null) {
            metrics.disposition(acceptedDisposition);
        }
        if (pendingFailure != null) {
            completeFailure(pendingFailure, dispositionFailure);
            return;
        }
        if (dispositionFailure != null) {
            completeFailure(dispositionFailure, null);
            return;
        }
        Throwable releaseFailure = releaseResources();
        if (releaseFailure != null) {
            recordTermination(TerminalOutcome.FAILED);
            completion.completeExceptionally(releaseFailure);
            return;
        }
        recordTermination(
            acceptedDisposition.action() == RecordDisposition.Action.COMMIT
                ? TerminalOutcome.COMMITTED
                : TerminalOutcome.RETAINED
        );
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

    private void completeFailure(Throwable failure, Throwable additionalFailure) {
        assertInMailbox();
        addSuppressed(failure, additionalFailure);
        addSuppressed(failure, releaseResources());
        recordTermination(TerminalOutcome.FAILED);
        completion.completeExceptionally(failure);
    }

    private static void addSuppressed(Throwable failure, Throwable additionalFailure) {
        if (additionalFailure != null && additionalFailure != failure) {
            failure.addSuppressed(additionalFailure);
        }
    }

    private void activateMetrics() {
        assertInMailbox();
        if (terminated || metricsActive) {
            return;
        }
        metricsActive = true;
        metrics.phaseChanged(phase, 1);
        metrics.runwayStateChanged(runwayState, 1);
    }

    private void transitionPhase(Phase nextPhase) {
        assertInMailbox();
        if (phase == nextPhase) {
            return;
        }
        if (metricsActive) {
            metrics.phaseChanged(phase, -1);
        }
        phase = nextPhase;
        if (metricsActive) {
            metrics.phaseChanged(phase, 1);
        }
    }

    private void recordTermination(TerminalOutcome outcome) {
        assertInMailbox();
        if (terminated) {
            return;
        }
        terminated = true;
        metrics.terminalOutcome(outcome);
        if (metricsActive) {
            metrics.phaseChanged(phase, -1);
            metrics.runwayStateChanged(runwayState, -1);
            metricsActive = false;
        }
    }

    private Throwable releaseResources() {
        if (resourcesReleased) {
            return null;
        }
        resourcesReleased = true;
        Throwable firstFailure = null;
        while (!ownedResources.isEmpty()) {
            var resource = ownedResources.removeLast();
            ownedResourceIdentities.remove(resource);
            var closeFailure = closeResource(resource);
            if (closeFailure != null) {
                if (firstFailure == null) {
                    firstFailure = closeFailure;
                } else {
                    firstFailure.addSuppressed(closeFailure);
                }
            }
        }
        return firstFailure;
    }

    private void adoptResource(AutoCloseable resource) {
        if (!ownedResourceIdentities.add(resource)) {
            throw new IllegalArgumentException("duplicate transaction resource for " + requestId);
        }
        ownedResources.addLast(resource);
    }

    private static Throwable closeResource(AutoCloseable resource) {
        try {
            resource.close();
            return null;
        } catch (Throwable t) {
            return t;
        }
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
