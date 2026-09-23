package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: AsyncPermitPool IReplayContexts RequestSenderOrchestrator TargetConnectionOwner . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.ProcessingCancellationResult;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ActorRequestTestUtils {
    public static <T> TrackedFuture<String, T> schedulePreparedRequest(
        RequestSenderOrchestrator orchestrator,
        PartitionGenerationId partitionGenerationId,
        IReplayContexts.IReplayerHttpTransactionContext context,
        Instant start,
        Duration interval,
        ByteBufListProducer packetProducer,
        RequestSenderOrchestrator.RetryVisitor<T> visitor,
        TargetConnectionOwner.RequestProcessingRegistration processingRegistration
    ) {
        return schedulePreparedRequest(
            orchestrator,
            partitionGenerationId,
            context,
            start,
            interval,
            packetProducer,
            visitor,
            new AsyncPermitPool(1, Runnable::run),
            processingRegistration
        );
    }

    public static <T> TrackedFuture<String, T> schedulePreparedRequest(
        RequestSenderOrchestrator orchestrator,
        PartitionGenerationId partitionGenerationId,
        IReplayContexts.IReplayerHttpTransactionContext context,
        Instant start,
        Duration interval,
        ByteBufListProducer packetProducer,
        RequestSenderOrchestrator.RetryVisitor<T> visitor,
        AsyncPermitPool permitPool,
        TargetConnectionOwner.RequestProcessingRegistration processingRegistration
    ) {
        var end = packetProducer.numByteBufs() > 1
            ? start.plus(interval.multipliedBy(packetProducer.numByteBufs() - 1L))
            : start;
        return orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            partitionGenerationId,
            context,
            start.minus(ReplayEngine.EXPECTED_TRANSFORMATION_DURATION),
            start,
            end,
            permitPool,
            () -> TextTrackedFuture.completedFuture(
                new TransformedOutputAndResult<>(
                    packetProducer,
                    HttpRequestTransformationStatus.completed()
                ),
                () -> "prepared test request"
            ),
            transformed -> visitor,
            ignored -> null,
            processingRegistration
        );
    }

*/
// REBUILD-LIMBO-END(G10)
    /**
     * Caller-controlled request-processing fixture.  Target-turn completion and tuple durability
     * are intentionally independent; tests must complete the processing milestone explicitly.
     */
// REBUILD-LIMBO-START(G10)
/*
    public static final class RequestProcessingFixture {
        private final CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome> completion =
            new CompletableFuture<>();
        private final CompletableFuture<Void> lifecycleHandled = new CompletableFuture<>();
        private boolean settled;
        private TargetConnectionOwner.RequestProcessingOutcome terminalOutcome;
        private final TargetConnectionOwner.RequestProcessingRegistration registration =
            new TargetConnectionOwner.RequestProcessingRegistration(
                completion.minimalCompletionStage(),
                this::cancel,
                lifecycleHandled
            );

        public TargetConnectionOwner.RequestProcessingRegistration registration() {
            return registration;
        }

        public synchronized boolean completeTupleDurable() {
            if (settled) {
                return false;
            }
            settled = true;
            terminalOutcome = new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable();
            completion.complete(terminalOutcome);
            return true;
        }

        public synchronized boolean failProcessing(Throwable failure) {
            Objects.requireNonNull(failure);
            if (settled) {
                return false;
            }
            settled = true;
            completion.completeExceptionally(failure);
            return true;
        }

        public CompletionStage<TargetConnectionOwner.RequestProcessingOutcome> completion() {
            return completion.minimalCompletionStage();
        }

        public CompletionStage<Void> lifecycleHandled() {
            return lifecycleHandled.minimalCompletionStage();
        }

        private synchronized CompletionStage<ProcessingCancellationResult> cancel(
            java.util.concurrent.CancellationException cause
        ) {
            var cancellationWon = !settled
                || terminalOutcome instanceof
                    TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished;
            if (!settled) {
                settled = true;
                terminalOutcome = new TargetConnectionOwner.RequestProcessingOutcome
                    .RequestCleanupFinished(cause);
                completion.complete(terminalOutcome);
            }
            return CompletableFuture.completedFuture(
                cancellationWon
                    ? new ProcessingCancellationResult.CancellationWon()
                    : new ProcessingCancellationResult.ProcessingCompletionWon()
            );
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)