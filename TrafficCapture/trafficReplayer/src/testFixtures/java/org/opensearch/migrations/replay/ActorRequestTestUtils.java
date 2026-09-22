package org.opensearch.migrations.replay;

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

    /**
     * Caller-controlled request-processing fixture.  Target-turn completion and tuple durability
     * are intentionally independent; tests must complete the processing milestone explicitly.
     */
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
