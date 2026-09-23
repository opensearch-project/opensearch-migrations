package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: TestEventLoop . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
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

class TargetConnectionOwnerMilestoneTest {
    @Test
    void connectionTurnAdvancesBeforeTupleDurabilityAndRegistryWaitsForProcessingAcceptance() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var lifecycleEvents = new ArrayList<String>();
        var processingAcceptance = new CompletableFuture<Void>();
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
                    return processingAcceptance;
                }
            }
        );
        var tupleDurable =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var prepared = new TestPrepared("request");
        var admission = admit(
            owner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(prepared)
            ),
            processing(tupleDurable)
        );
        var close = owner.admitCloseWithAcceptance(
            PARTITION_GENERATION,
            1,
            Instant.EPOCH
        );
        eventLoop.runUntilIdle();

        exchange.completeNext(new RequestTurnResult.Completed<>("response"));
        eventLoop.runUntilIdle();

        Assertions.assertTrue(
            admission.turnCompletion().toCompletableFuture().isDone()
        );
        Assertions.assertTrue(
            close.closeCompletion().toCompletableFuture().isDone(),
            "ordered close must not wait for tuple durability"
        );
        Assertions.assertEquals(1, exchange.closeCalls);
        Assertions.assertFalse(owner.termination().toCompletableFuture().isDone());
        Assertions.assertEquals(List.of("turn:0"), lifecycleEvents);
        Assertions.assertEquals(1, prepared.connectionTurnFinishedCount);
        Assertions.assertEquals(0, prepared.closeCount);

        tupleDurable.complete(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );
        eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn:0", "processing:0"), lifecycleEvents);
        Assertions.assertEquals(1, prepared.closeCount);
        Assertions.assertFalse(
            owner.termination().toCompletableFuture().isDone(),
            "the registry remains owned until the processing milestone is accepted"
        );

        processingAcceptance.complete(null);
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            SessionOutcome.Closed.class,
            owner.termination().toCompletableFuture().join()
        );
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void connectionMilestoneAcceptanceDoesNotSerializeTheNextTargetTurn() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var firstConnectionAcceptance = new CompletableFuture<Void>();
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
                    return requestId.requestIndex() == 0
                        ? firstConnectionAcceptance
                        : CompletableFuture.completedFuture(null);
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
        var firstProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var secondProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var first = admit(
            owner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("first"))
            ),
            processing(firstProcessing)
        );
        admit(
            owner,
            request(1),
            1,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("second"))
            ),
            processing(secondProcessing)
        );
        eventLoop.runUntilIdle();

        exchange.completeNext(new RequestTurnResult.Completed<>("first-response"));
        eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn:0"), lifecycleEvents);
        Assertions.assertFalse(
            first.turnCompletion().toCompletableFuture().isDone()
        );
        Assertions.assertEquals(List.of("first", "second"), exchange.executed);

        firstConnectionAcceptance.complete(null);
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            RequestTurnResult.Completed.class,
            first.turnCompletion().toCompletableFuture().join()
        );
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void processingMilestoneWaitsForConnectionMilestoneAcceptance() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var connectionAcceptance = new CompletableFuture<Void>();
        var lifecycleEvents = new ArrayList<String>();
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
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
                    lifecycleEvents.add("turn");
                    return connectionAcceptance;
                }

                @Override
                public CompletionStage<Void> requestProcessingFinished(
                    PartitionGenerationId partitionGenerationId,
                    ReplayRequestId requestId
                ) {
                    lifecycleEvents.add("processing");
                    return CompletableFuture.completedFuture(null);
                }
            }
        );
        admit(
            owner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("request"))
            ),
            processing(processingCompletion)
        );
        eventLoop.runUntilIdle();

        exchange.completeNext(new RequestTurnResult.Completed<>("response"));
        eventLoop.runUntilIdle();
        processingCompletion.complete(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );
        eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn"), lifecycleEvents);

        connectionAcceptance.complete(null);
        eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn", "processing"), lifecycleEvents);
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void tupleDurabilityBeforeConnectionTurnIsProcessFatal() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var owner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            TargetConnectionOwnerTestSupport.acceptingLifecycleSink()
        );
        admit(
            owner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("request"))
            ),
            processing(processingCompletion)
        );
        eventLoop.runUntilIdle();

        processingCompletion.complete(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );
        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getMessage().contains(
                "request-processing completion before connection turn"
            )
        );
    }
}

*/
// REBUILD-LIMBO-END(G10)