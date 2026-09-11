package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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

    private static final class PendingCommand {
        private final CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        private final Runnable transition;

        private PendingCommand(Runnable transition) {
            this.transition = transition;
        }
    }

    private static final class EvidenceHandoff {
        private EvidenceOutcome outcome;
        private Throwable failure;
        private boolean ready;
        private boolean claimed;
    }

    private static final class DispositionWork {
        private final Decision decision;
        private final RecordDisposition requestedDisposition;
        private final List<CompletableFuture<RecordDispositionLedger.DispositionResult>> stages;
        private final CompletableFuture<Void> aggregate;
        private Throwable failure;
        private boolean ready;
        private boolean claimed;

        private DispositionWork(
            Decision decision,
            RecordDisposition requestedDisposition,
            List<CompletableFuture<RecordDispositionLedger.DispositionResult>> stages
        ) {
            this.decision = decision;
            this.requestedDisposition = requestedDisposition;
            this.stages = stages;
            this.aggregate = CompletableFuture.allOf(stages.toArray(CompletableFuture[]::new));
        }
    }

    private final Object stateLock = new Object();
    private final ReplayRequestId requestId;
    private final String ledgerOwner;
    private final ActorMailbox mailbox;
    private final OwnerThreadGuard ownerThreadGuard;
    private final EvidenceWriter<R> evidenceWriter;
    private final ReplayDispositionPolicy dispositionPolicy;
    private final RecordDispositionLedger dispositionLedger;
    private final LinkedHashSet<RecordId> recordIds = new LinkedHashSet<>();
    private final Deque<AutoCloseable> ownedResources = new ArrayDeque<>();
    private final Set<AutoCloseable> ownedResourceIdentities =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<PendingCommand> pendingCommands =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final CompletionGate<TransactionOutcome> completion = new CompletionGate<>();
    private final Metrics metrics;
    private SourceOutcome sourceOutcome;
    private TargetOutcome<R> targetOutcome;
    private EvidenceOutcome evidenceOutcome;
    private EvidenceHandoff evidenceHandoff;
    private DispositionWork dispositionWork;
    private Phase phase = Phase.WAITING_FOR_JOIN;
    private RunwayState runwayState = RunwayState.AVAILABLE;
    private Throwable pendingFailure;
    private boolean sourceSettlementReserved;
    private boolean targetSettlementReserved;
    private boolean failureReserved;
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
        this.ownerThreadGuard = new OwnerThreadGuard(
            "replay transaction " + requestId,
            mailbox::inMailbox
        );
        this.evidenceWriter = evidenceWriter;
        this.dispositionPolicy = dispositionPolicy;
        this.dispositionLedger = dispositionLedger;
        this.metrics = metrics;
        try {
            for (var recordId : recordIds) {
                if (!this.recordIds.add(recordId)) {
                    throw new IllegalArgumentException(
                        "duplicate transaction record for " + requestId + ": " + recordId
                    );
                }
            }
            resources.forEach(this::adoptResourceLocked);
        } catch (RuntimeException | Error failure) {
            Throwable cleanupFailure;
            synchronized (stateLock) {
                terminated = true;
                cleanupFailure = releaseResourcesLocked();
                completion.completeExceptionally(failure);
            }
            addSuppressed(failure, cleanupFailure);
            throw failure;
        }
        mailbox.execute(this::activateMetricsFromMailbox);
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
        PendingCommand command;
        synchronized (stateLock) {
            var unavailable = unavailableFailureLocked();
            if (unavailable != null) {
                return failedAcknowledgement(unavailable);
            }
            if (sourceSettlementReserved) {
                return failedAcknowledgement(
                    new IllegalStateException("source outcome already settled for " + requestId)
                );
            }
            var stagedRecords = new LinkedHashSet<RecordId>();
            for (var recordId : additionalRecordIds) {
                if (!stagedRecords.add(recordId) || recordIds.contains(recordId)) {
                    return failedAcknowledgement(
                        new IllegalStateException(
                            "record already belongs to " + requestId + ": " + recordId
                        )
                    );
                }
            }
            recordIds.addAll(stagedRecords);
            sourceSettlementReserved = true;
            command = reserveCommandLocked(() -> {
                sourceOutcome = outcome;
                tryAdvanceLocked();
            });
        }
        return enqueueCommand(command);
    }

    public CompletionStage<Void> settleTarget(@NonNull TargetOutcome<R> outcome) {
        PendingCommand command;
        synchronized (stateLock) {
            var unavailable = unavailableFailureLocked();
            if (unavailable != null) {
                return failedAcknowledgement(unavailable);
            }
            if (targetSettlementReserved) {
                return failedAcknowledgement(
                    new IllegalStateException("target outcome already settled for " + requestId)
                );
            }
            targetSettlementReserved = true;
            command = reserveCommandLocked(() -> {
                targetOutcome = outcome;
                tryAdvanceLocked();
            });
        }
        return enqueueCommand(command);
    }

    public CompletionStage<Void> ownResource(@NonNull AutoCloseable resource) {
        PendingCommand command;
        synchronized (stateLock) {
            var unavailable = unavailableFailureLocked();
            if (unavailable != null) {
                var closeFailure = closeResource(resource);
                addSuppressed(unavailable, closeFailure);
                return failedAcknowledgement(unavailable);
            }
            if (!ownedResourceIdentities.add(resource)) {
                return failedAcknowledgement(
                    new IllegalStateException("transaction already owns resource for " + requestId)
                );
            }
            ownedResources.addLast(resource);
            command = reserveCommandLocked(() -> {
                // Ownership was recorded before admission so mailbox loss can sweep it.
            });
        }
        return enqueueCommand(command);
    }

    public CompletionStage<TransactionOutcome> completion() {
        return completion.stage();
    }

    public CompletionStage<Void> observeRunwayLost(@NonNull RunwayLossReason reason) {
        PendingCommand command;
        synchronized (stateLock) {
            var unavailable = unavailableFailureLocked();
            if (unavailable != null) {
                return failedAcknowledgement(unavailable);
            }
            command = reserveCommandLocked(() -> {
                if (runwayState == RunwayState.AVAILABLE) {
                    if (metricsActive) {
                        metrics.runwayStateChanged(runwayState, -1);
                    }
                    runwayState = RunwayState.LOST;
                    if (metricsActive) {
                        metrics.runwayStateChanged(runwayState, 1);
                        metrics.runwayLost(reason);
                    }
                }
            });
        }
        return enqueueCommand(command);
    }

    public CompletionStage<Void> fail(@NonNull Throwable cause) {
        PendingCommand command;
        synchronized (stateLock) {
            var unavailable = unavailableFailureLocked();
            if (unavailable != null) {
                return failedAcknowledgement(unavailable);
            }
            if (failureReserved) {
                return failedAcknowledgement(
                    new IllegalStateException("transaction failure already recorded for " + requestId)
                );
            }
            failureReserved = true;
            pendingFailure = cause;
            command = reserveCommandLocked(() -> {
                if (phase == Phase.DISPOSING) {
                    return;
                }
                if (recordIds.isEmpty()) {
                    completeFailureLocked(cause, null);
                } else {
                    beginFailureDispositionLocked();
                }
            });
        }
        return enqueueCommand(command);
    }

    private PendingCommand reserveCommandLocked(Runnable transition) {
        var command = new PendingCommand(transition);
        pendingCommands.add(command);
        return command;
    }

    private CompletionStage<Void> enqueueCommand(PendingCommand command) {
        try {
            mailbox.execute(() -> runCommand(command));
        } catch (RuntimeException | Error admissionFailure) {
            command.acknowledgement.completeExceptionally(admissionFailure);
        }
        return command.acknowledgement.minimalCompletionStage();
    }

    private void runCommand(PendingCommand command) {
        synchronized (stateLock) {
            if (!pendingCommands.remove(command)) {
                return;
            }
            if (terminated) {
                command.acknowledgement.completeExceptionally(unavailableFailureLocked());
                return;
            }
            assertInMailbox();
            command.transition.run();
            command.acknowledgement.complete(null);
        }
    }

    private void activateMetricsFromMailbox() {
        synchronized (stateLock) {
            if (terminated || metricsActive) {
                return;
            }
            assertInMailbox();
            metricsActive = true;
            metrics.phaseChanged(phase, 1);
            metrics.runwayStateChanged(runwayState, 1);
        }
    }

    private void tryAdvanceLocked() {
        assertInMailbox();
        if (phase != Phase.WAITING_FOR_JOIN || sourceOutcome == null || targetOutcome == null) {
            return;
        }
        if (!dispositionPolicy.requiresEvidence(sourceOutcome, targetOutcome)) {
            evidenceOutcome = new EvidenceOutcome.NotRequired("teardown");
            beginDispositionLocked();
            return;
        }

        transitionPhaseLocked(Phase.WRITING_EVIDENCE);
        var handoff = new EvidenceHandoff();
        evidenceHandoff = handoff;
        CompletionStage<EvidenceOutcome> evidenceStage;
        try {
            evidenceStage = evidenceWriter.write(requestId, sourceOutcome, targetOutcome);
            if (evidenceStage == null) {
                evidenceStage = CompletableFuture.completedFuture(
                    new EvidenceOutcome.Failed(
                        new NullPointerException("evidence writer returned no completion stage")
                    )
                );
            }
        } catch (Exception failure) {
            evidenceStage = CompletableFuture.completedFuture(new EvidenceOutcome.Failed(failure));
        }
        evidenceStage.whenComplete((outcome, failure) ->
            stageEvidenceCompletion(handoff, outcome, failure)
        );
    }

    private void stageEvidenceCompletion(
        EvidenceHandoff handoff,
        EvidenceOutcome outcome,
        Throwable failure
    ) {
        synchronized (stateLock) {
            if (handoff != evidenceHandoff || handoff.claimed || terminated) {
                return;
            }
            handoff.outcome = outcome;
            handoff.failure = failure == null ? null : unwrap(failure);
            handoff.ready = true;
        }
        postCallback(() -> consumeEvidence(handoff));
    }

    private void consumeEvidence(EvidenceHandoff handoff) {
        synchronized (stateLock) {
            if (handoff != evidenceHandoff
                || handoff.claimed
                || !handoff.ready
                || terminated) {
                return;
            }
            assertInMailbox();
            handoff.claimed = true;
            evidenceOutcome = handoff.failure == null
                ? handoff.outcome
                : new EvidenceOutcome.Failed(handoff.failure);
            if (evidenceOutcome == null) {
                evidenceOutcome = new EvidenceOutcome.Failed(
                    new NullPointerException("evidence writer completed without an outcome")
                );
            }
            beginDispositionLocked();
        }
    }

    private void beginDispositionLocked() {
        assertInMailbox();
        transitionPhaseLocked(Phase.DISPOSING);
        var decision = dispositionPolicy.decide(sourceOutcome, targetOutcome, evidenceOutcome);
        startDispositionLocked(decision, decision.disposition());
    }

    /**
     * A failing transaction still owes its record obligations a disposition. Failure never carries
     * commit authority, so every record that has not already entered disposition retains.
     */
    private void beginFailureDispositionLocked() {
        assertInMailbox();
        transitionPhaseLocked(Phase.DISPOSING);
        startDispositionLocked(
            null,
            new RecordDisposition.Retain("transaction-failed")
        );
    }

    @SuppressWarnings("java:S1181") // Every record must receive a failed disposition stage, even on Error.
    private void startDispositionLocked(
        Decision decision,
        RecordDisposition requestedDisposition
    ) {
        var stages = new ArrayList<CompletableFuture<RecordDispositionLedger.DispositionResult>>();
        for (var recordId : recordIds) {
            try {
                stages.add(
                    dispositionLedger.dispose(recordId, ledgerOwner, requestedDisposition)
                        .toCompletableFuture()
                );
            } catch (RuntimeException | Error failure) {
                stages.add(CompletableFuture.failedFuture(failure));
            }
        }
        var work = new DispositionWork(
            decision,
            requestedDisposition,
            stages
        );
        dispositionWork = work;
        work.aggregate.whenComplete((ignored, failure) ->
            stageDispositionCompletion(work, failure)
        );
    }

    private void stageDispositionCompletion(DispositionWork work, Throwable failure) {
        synchronized (stateLock) {
            if (work != dispositionWork || work.claimed || terminated) {
                return;
            }
            work.failure = failure == null ? null : unwrap(failure);
            work.ready = true;
        }
        postCallback(() -> consumeDisposition(work));
    }

    private void consumeDisposition(DispositionWork work) {
        synchronized (stateLock) {
            if (work != dispositionWork || work.claimed || !work.ready || terminated) {
                return;
            }
            assertInMailbox();
            work.claimed = true;
            finishDispositionLocked(work);
        }
    }

    @SuppressWarnings("java:S1181") // Metrics failure is aggregated into the transaction result.
    private void finishDispositionLocked(DispositionWork work) {
        var acceptedDisposition = work.failure == null
            ? acceptedDisposition(work.requestedDisposition, work.stages)
            : work.requestedDisposition;
        Throwable metricsFailure = null;
        if (work.failure == null) {
            try {
                metrics.disposition(acceptedDisposition);
            } catch (RuntimeException | Error failure) {
                metricsFailure = failure;
            }
        }

        if (pendingFailure != null) {
            addSuppressed(pendingFailure, metricsFailure);
            completeFailureLocked(pendingFailure, work.failure);
            return;
        }
        if (work.failure != null) {
            completeFailureLocked(work.failure, work.failure);
            return;
        }
        if (metricsFailure != null) {
            completeFailureLocked(metricsFailure, metricsFailure);
            return;
        }
        finishSuccessfullyLocked(work.decision, acceptedDisposition);
    }

    private RecordDisposition acceptedDisposition(
        RecordDisposition requestedDisposition,
        List<CompletableFuture<RecordDispositionLedger.DispositionResult>> dispositionStages
    ) {
        var acceptedDispositions = dispositionStages.stream()
            .map(CompletableFuture::join)
            .map(RecordDispositionLedger.DispositionResult::disposition)
            .toList();
        for (var disposition : acceptedDispositions) {
            if (disposition instanceof RecordDisposition.Retain) {
                return disposition;
            }
        }
        return requestedDisposition;
    }

    private void finishSuccessfullyLocked(
        Decision decision,
        RecordDisposition acceptedDisposition
    ) {
        var releaseFailure = releaseResourcesLocked();
        var terminalOutcome = terminalOutcomeFor(acceptedDisposition, releaseFailure);
        var metricsFailure = recordTerminationLocked(terminalOutcome);
        addSuppressed(releaseFailure, metricsFailure);
        if (releaseFailure != null) {
            completion.completeExceptionally(releaseFailure);
            return;
        }
        if (metricsFailure != null) {
            completion.completeExceptionally(metricsFailure);
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

    private static TerminalOutcome terminalOutcomeFor(
        RecordDisposition acceptedDisposition,
        Throwable releaseFailure
    ) {
        if (releaseFailure != null) {
            return TerminalOutcome.FAILED;
        }
        return acceptedDisposition.action() == RecordDisposition.Action.COMMIT
            ? TerminalOutcome.COMMITTED
            : TerminalOutcome.RETAINED;
    }

    private void completeFailureLocked(Throwable failure, Throwable dispositionFailure) {
        addSuppressed(failure, dispositionFailure);
        var releaseFailure = releaseResourcesLocked();
        addSuppressed(failure, releaseFailure);
        var metricsFailure = recordTerminationLocked(TerminalOutcome.FAILED);
        addSuppressed(failure, metricsFailure);
        completion.completeExceptionally(failure);
    }

    @SuppressWarnings("java:S1181") // All terminal metrics are attempted and their failures aggregated.
    private Throwable recordTerminationLocked(TerminalOutcome outcome) {
        if (terminated) {
            return null;
        }
        terminated = true;
        Throwable firstFailure = null;
        try {
            metrics.terminalOutcome(outcome);
        } catch (RuntimeException | Error failure) {
            firstFailure = failure;
        }
        if (metricsActive) {
            try {
                metrics.phaseChanged(phase, -1);
            } catch (RuntimeException | Error failure) {
                firstFailure = aggregate(firstFailure, failure);
            }
            try {
                metrics.runwayStateChanged(runwayState, -1);
            } catch (RuntimeException | Error failure) {
                firstFailure = aggregate(firstFailure, failure);
            }
            metricsActive = false;
        }
        return firstFailure;
    }

    private void transitionPhaseLocked(Phase nextPhase) {
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

    private void postCallback(Runnable command) {
        mailbox.execute(command);
    }

    private Throwable releaseResourcesLocked() {
        if (resourcesReleased) {
            return null;
        }
        resourcesReleased = true;
        Throwable firstFailure = null;
        while (!ownedResources.isEmpty()) {
            var resource = ownedResources.removeLast();
            ownedResourceIdentities.remove(resource);
            firstFailure = aggregate(firstFailure, closeResource(resource));
        }
        return firstFailure;
    }

    private void adoptResourceLocked(AutoCloseable resource) {
        if (!ownedResourceIdentities.add(resource)) {
            throw new IllegalArgumentException("duplicate transaction resource for " + requestId);
        }
        ownedResources.addLast(resource);
    }

    private Throwable unavailableFailureLocked() {
        if (!terminated) {
            return null;
        }
        return new IllegalStateException(ALREADY_TERMINATED + requestId);
    }

    private static CompletionStage<Void> failedAcknowledgement(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    @SuppressWarnings("java:S1181") // Cleanup aggregates Error with other close failures.
    private static Throwable closeResource(AutoCloseable resource) {
        try {
            resource.close();
            return null;
        } catch (Exception | Error failure) {
            return failure;
        }
    }

    private static Throwable aggregate(Throwable firstFailure, Throwable additionalFailure) {
        if (firstFailure == null) {
            return additionalFailure;
        }
        addSuppressed(firstFailure, additionalFailure);
        return firstFailure;
    }

    private static void addSuppressed(Throwable failure, Throwable additionalFailure) {
        if (failure != null && additionalFailure != null && additionalFailure != failure) {
            failure.addSuppressed(additionalFailure);
        }
    }

    private void assertInMailbox() {
        ownerThreadGuard.requireOwnerThread();
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
