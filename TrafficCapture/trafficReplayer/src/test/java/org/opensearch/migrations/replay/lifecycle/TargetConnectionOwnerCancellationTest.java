package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.ProcessingCancellationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestTurnResult;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.TestExchange;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.TestPrepared;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.PARTITION_GENERATION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.admit;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.owner;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.processing;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.request;

class TargetConnectionOwnerCancellationTest {
    @Test
    void cancellationBeforeSendEmitsCleanupAndNoNormalMilestones() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var lifecycleEvents = new ArrayList<String>();
        var owner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            new TargetConnectionOwner.RequestLifecycleSink() {
                @Override
                public CompletionStage<Void> connectionRequestFinished(
                    PartitionGenerationId partitionGenerationId,
                    ReplayRequestId requestId
                ) {
                    lifecycleEvents.add("turn:" + requestId.requestIndex());
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> requestProcessingFinished(
                    PartitionGenerationId partitionGenerationId,
                    ReplayRequestId requestId
                ) {
                    lifecycleEvents.add("processing:" + requestId.requestIndex());
                    return CompletableFuture.completedFuture(null);
                }
            }
        );
        var activeProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        admit(
            owner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("active"))
            ),
            processing(activeProcessing)
        );
        var queuedPrepared = new TestPrepared("queued");
        var preparationCancellations = new AtomicInteger();
        var queuedPreparation =
            new TargetConnectionOwner.RequestPreparation<TestPrepared>() {
                @Override
                public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                    return CompletableFuture.completedFuture(
                        new PreparationOutcome.Prepared<>(queuedPrepared)
                    );
                }

                @Override
                public CompletionStage<Void> cancel(CancellationException cause) {
                    preparationCancellations.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
            };
        var processingCancellations = new AtomicInteger();
        var queuedProcessingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var queuedProcessingAcceptance =
            new CompletableFuture<ProcessingCancellationResult>();
        var queuedProcessing = new TargetConnectionOwner.RequestProcessingRegistration(
            queuedProcessingCompletion,
            cause -> {
                processingCancellations.incrementAndGet();
                queuedProcessingCompletion.complete(
                    new TargetConnectionOwner.RequestProcessingOutcome
                        .RequestCleanupFinished(cause)
                );
                return queuedProcessingAcceptance;
            }
        );
        var queued = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(1),
            1,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH,
            queuedPreparation,
            queuedProcessing
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("active"), exchange.executed);

        var cancellation = new CancellationException("generation cancelled");
        var termination = owner.abort(
            AbortReason.SOURCE_REASSIGNMENT,
            cancellation
        ).toCompletableFuture();
        eventLoop.runUntilIdle();

        var cancelled = Assertions.assertInstanceOf(
            RequestTurnResult.Cancelled.class,
            queued.turnCompletion().toCompletableFuture().join()
        );
        Assertions.assertSame(cancellation, cancelled.cause());
        Assertions.assertEquals(1, preparationCancellations.get());
        Assertions.assertEquals(1, processingCancellations.get());
        Assertions.assertEquals(1, queuedPrepared.closeCount);
        Assertions.assertEquals(List.of("active"), exchange.executed);
        Assertions.assertTrue(lifecycleEvents.isEmpty());
        Assertions.assertFalse(termination.isDone());

        exchange.abortCompletion.complete(null);
        eventLoop.runUntilIdle();
        Assertions.assertFalse(
            termination.isDone(),
            "registry ownership must remain until typed processing cancellation is accepted"
        );

        queuedProcessingAcceptance.complete(
            new ProcessingCancellationResult.CancellationWon()
        );
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(SessionOutcome.Aborted.class, termination.join());
        Assertions.assertTrue(lifecycleEvents.isEmpty());
        Assertions.assertTrue(fatalFailures.isEmpty());
    }
}
