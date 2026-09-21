package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
        FINALIZING("finalizing");

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
        COMPLETED("completed"),
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

        };

        void phaseChanged(Phase phase, int delta);

        void runwayStateChanged(RunwayState state, int delta);

        void runwayLost(RunwayLossReason reason);

        void terminalOutcome(TerminalOutcome outcome);

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

    private final Object stateLock = new Object();
    private final ReplayRequestId requestId;
    private final ActorMailbox mailbox;
    private final OwnerThreadGuard ownerThreadGuard;
    private final EvidenceWriter<R> evidenceWriter;
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
        @NonNull Collection<? extends AutoCloseable> resources
    ) {
        this(
            requestId,
            mailbox,
            evidenceWriter,
            resources,
            Metrics.NOOP
        );
    }

    public ReplayTransaction(
        @NonNull ReplayRequestId requestId,
        @NonNull ActorMailbox mailbox,
        @NonNull EvidenceWriter<R> evidenceWriter,
        @NonNull Collection<? extends AutoCloseable> resources,
        @NonNull Metrics metrics
    ) {
        this.requestId = requestId;
        this.mailbox = mailbox;
        this.ownerThreadGuard = new OwnerThreadGuard(
            "replay transaction " + requestId,
            mailbox::inMailbox
        );
        this.evidenceWriter = evidenceWriter;
        this.metrics = metrics;
        try {
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

    public CompletionStage<Void> settleSource(@NonNull SourceOutcome outcome) {
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
                completeFailureLocked(cause);
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
        if (!requiresEvidence(sourceOutcome, targetOutcome)) {
            evidenceOutcome = new EvidenceOutcome.NotRequired("teardown");
            finishSuccessfullyLocked();
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
            evidenceOutcome.visit(new EvidenceOutcome.Visitor<>() {
                @Override
                public Void onDurable(EvidenceOutcome.Durable durable) {
                    finishSuccessfullyLocked();
                    return null;
                }

                @Override
                public Void onFailed(EvidenceOutcome.Failed failed) {
                    completeFailureLocked(failed.cause());
                    return null;
                }

                @Override
                public Void onNotRequired(EvidenceOutcome.NotRequired notRequired) {
                    finishSuccessfullyLocked();
                    return null;
                }
            });
        }
    }

    private void finishSuccessfullyLocked() {
        assertInMailbox();
        transitionPhaseLocked(Phase.FINALIZING);
        var releaseFailure = releaseResourcesLocked();
        var terminalOutcome = releaseFailure == null
            ? TerminalOutcome.COMPLETED
            : TerminalOutcome.FAILED;
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
                evidenceOutcome
            )
        );
    }

    private void completeFailureLocked(Throwable failure) {
        assertInMailbox();
        transitionPhaseLocked(Phase.FINALIZING);
        var releaseFailure = releaseResourcesLocked();
        addSuppressed(failure, releaseFailure);
        var metricsFailure = recordTerminationLocked(TerminalOutcome.FAILED);
        addSuppressed(failure, metricsFailure);
        completion.completeExceptionally(failure);
    }

    private static <T> boolean requiresEvidence(
        SourceOutcome source,
        TargetOutcome<T> target
    ) {
        Boolean sourceRequiresEvidence = source.visit(new SourceOutcome.Visitor<Boolean>() {
            @Override
            public Boolean onComplete(SourceOutcome.Complete complete) {
                return true;
            }

            @Override
            public Boolean onConfirmedDead(SourceOutcome.ConfirmedDead confirmedDead) {
                return true;
            }

            @Override
            public Boolean onCapturedClose(SourceOutcome.CapturedClose capturedClose) {
                return true;
            }

            @Override
            public Boolean onLegacyExpired(SourceOutcome.LegacyExpired legacyExpired) {
                return true;
            }

            @Override
            public Boolean onInconclusive(SourceOutcome.Inconclusive inconclusive) {
                return true;
            }

            @Override
            public Boolean onInterrupted(SourceOutcome.Interrupted interrupted) {
                return false;
            }

            @Override
            public Boolean onShutdown(SourceOutcome.Shutdown shutdown) {
                return false;
            }
        });
        return Boolean.TRUE.equals(sourceRequiresEvidence)
            && !(target instanceof TargetOutcome.Cancelled<?>);
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
