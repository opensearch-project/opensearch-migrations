package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.TestExchange;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.TestPrepared;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.admit;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.owner;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.processing;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.request;

class TargetConnectionOwnerFirstWriteTest {
    @Test
    void firstTargetWriteIsConnectionLocalAndExactlyOnce() {
        var eventLoop = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var lifecycleEvents = new ArrayList<String>();
        var connectionOwner = owner(
            eventLoop,
            new TestExchange(),
            fatalFailures,
            recordingLifecycleSink(lifecycleEvents)
        );
        admit(
            connectionOwner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("request"))
            ),
            processing(new CompletableFuture<>())
        );
        eventLoop.runUntilIdle();

        connectionOwner.firstTargetWriteSubmitted(request(0));
        eventLoop.runUntilIdle();

        Assertions.assertTrue(fatalFailures.isEmpty());
        Assertions.assertTrue(
            lifecycleEvents.isEmpty(),
            "FirstTargetWriteSubmitted must never cross into replay intake"
        );

        connectionOwner.firstTargetWriteSubmitted(request(0));
        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getMessage().contains("first target write")
        );
        Assertions.assertTrue(lifecycleEvents.isEmpty());
    }

    @Test
    void ownerThreadFirstWriteTransitionIsImmediate() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var connectionOwner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            TargetConnectionOwnerTestSupport.acceptingLifecycleSink()
        );
        var executeCallbacks = new AtomicInteger();
        var pendingTasksBeforeFirstWrite = new AtomicInteger(-1);
        var pendingTasksAfterFirstWrite = new AtomicInteger(-1);
        var fatalFailuresAfterFirstWrite = new AtomicInteger(-1);
        var fatalFailuresAfterDuplicate = new AtomicInteger(-1);
        exchange.onExecute = requestId -> {
            pendingTasksBeforeFirstWrite.set(eventLoop.pendingTasks());
            connectionOwner.firstTargetWriteSubmitted(requestId);
            pendingTasksAfterFirstWrite.set(eventLoop.pendingTasks());
            fatalFailuresAfterFirstWrite.set(fatalFailures.size());
            connectionOwner.firstTargetWriteSubmitted(requestId);
            fatalFailuresAfterDuplicate.set(fatalFailures.size());
            executeCallbacks.incrementAndGet();
        };
        admit(
            connectionOwner,
            request(0),
            0,
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("request"))
            ),
            processing(new CompletableFuture<>())
        );

        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, executeCallbacks.get());
        Assertions.assertEquals(
            pendingTasksBeforeFirstWrite.get(),
            pendingTasksAfterFirstWrite.get(),
            "an owner-thread observation must not enqueue another mailbox turn"
        );
        Assertions.assertEquals(
            0,
            fatalFailuresAfterFirstWrite.get(),
            "the first observation must succeed before the duplicate is attempted"
        );
        Assertions.assertEquals(
            1,
            fatalFailuresAfterDuplicate.get(),
            "the duplicate must observe the first transition before execute returns"
        );
        Assertions.assertEquals(
            "first target write was submitted more than once",
            fatalFailures.get(0).getCause().getMessage()
        );
    }

    private static TargetConnectionOwner.RequestLifecycleSink recordingLifecycleSink(
        List<String> lifecycleEvents
    ) {
        return new TargetConnectionOwner.RequestLifecycleSink() {
            @Override
            public CompletionStage<Void> connectionRequestFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                lifecycleEvents.add("turn");
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> requestProcessingFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                lifecycleEvents.add("processing");
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
