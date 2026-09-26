/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.CONNECTION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.GENERATION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.request;

class TargetConnectionOwnerCancellationTest {
    @Test
    void shutdownGraceDrainsPartlyWrittenAndQueuedRequestsThroughOrdinaryCompletion() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(20, Instant.EPOCH);
        fixture.admit(21, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(20);
        fixture.preparer.ready(21);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.GracefulConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            CancellationGrace.Shutdown.INSTANCE,
            new CancellationException("orderly shutdown")
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            0,
            fixture.eventLoop.pendingTimers(),
            "shutdown grace is host-bounded and must not install a process-local cancellation deadline"
        );
        Assertions.assertEquals(1, fixture.tupleSink.flushes, "shutdown grace must flush on entry");
        Assertions.assertEquals(0, fixture.targetChannel.attempt(0).abortCalls);
        Assertions.assertEquals(
            1,
            fixture.targetChannel.attempts.size(),
            "the queued request remains admitted but waits for the active turn"
        );

        fixture.completeSource(20, "source-response-20");
        fixture.targetChannel.attempt(0).targetResponse("target-response-20");
        fixture.eventLoop.runUntilIdle();
        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            2,
            fixture.targetChannel.attempts.size(),
            "shutdown must allow the already-admitted queued request to begin"
        );
        Assertions.assertEquals(0, fixture.targetChannel.attempt(1).abortCalls);
        fixture.completeSource(21, "source-response-21");
        fixture.targetChannel.attempt(1).targetResponse("target-response-21");
        fixture.eventLoop.runUntilIdle();
        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            java.util.List.of(
                "turn:20",
                "processing:20",
                "turn:21",
                "processing:21",
                "owner-finished"
            ),
            fixture.lifecycleEvents,
            "a drained shutdown emits only ordinary lifecycle milestones"
        );
        Assertions.assertEquals(
            3,
            fixture.tupleSink.flushes,
            "grace entry and both accepted tuples must each flush"
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void connectionCleanupWaitsForEveryOwnerOperationRegistration() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        var blocker = new AtomicReference<OutstandingOperationRegistry.Registration>();
        fixture.eventLoop.execute(() -> blocker.set(fixture.owner.operations().register(
            GENERATION,
            CONNECTION,
            null,
            OperationType.CANCELLATION_CLEANUP,
            null,
            OutstandingOperationRegistry.WaitReason.WAITING_FOR_CHANNEL_TEARDOWN
        )));
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new CancellationException("generation cancelled")
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(fixture.lifecycleEvents.isEmpty());

        fixture.eventLoop.execute(() ->
            fixture.owner.operations().complete(blocker.get())
        );
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            java.util.List.of("cleanup-finished"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void durableTupleBeforeForceCompletesNormallyAfterTurnAcceptance() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        var turnAcceptance = new java.util.concurrent.CompletableFuture<Void>();
        fixture.lifecycleAcceptances.put("turn:5", turnAcceptance);
        fixture.admit(5, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(5);
        fixture.completeSource(5, "source-response");
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();
        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new CancellationException("force after durability")
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            java.util.List.of("turn:5"),
            fixture.lifecycleEvents
        );

        turnAcceptance.complete(null);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            java.util.List.of("turn:5", "processing:5", "cleanup-finished"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void durableFromASeparatelyOwnedTupleWriterAfterForceEmitsCleanupExactlyOnce() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture(1, true);
        fixture.admit(12, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(12);
        fixture.completeSource(12, "source-response");
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(java.util.List.of("turn:12"), fixture.lifecycleEvents);
        fixture.tupleEventLoop.runUntilIdle();
        Assertions.assertEquals(1, fixture.tupleSink.writes.size());

        fixture.tupleSink.durableNext();
        Assertions.assertEquals(1, fixture.tupleEventLoop.pendingTasks());

        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new CancellationException("force before durability is applied")
        ));
        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(java.util.List.of("turn:12"), fixture.lifecycleEvents);

        fixture.tupleEventLoop.runUntilIdle();
        Assertions.assertEquals(1, fixture.eventLoop.pendingTasks());

        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            java.util.List.of("turn:12", "cleanup-finished"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void forceCancellationReleasesFinalSourceWaitBeforeCleanup() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(6, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(6);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(
            fixture.owner.activitySnapshot().stream().anyMatch(snapshot ->
                snapshot.operationType() == OperationType.FINAL_SOURCE_RESPONSE_WAIT
            )
        );

        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new CancellationException("force while waiting for final source")
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(
            fixture.owner.activitySnapshot().stream().noneMatch(snapshot ->
                snapshot.operationType() == OperationType.FINAL_SOURCE_RESPONSE_WAIT
            )
        );
        Assertions.assertEquals(
            java.util.List.of("turn:6", "cleanup-finished"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void gracefulCancellationUsesInjectedMonotonicClockAndFinishesBeforeDeadline() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(8, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(8);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.targetChannel.attempt(0).finalWrite();
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.GracefulConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new org.opensearch.migrations.replay.identity.CancellationGrace.Revocation(
                new CancellationDeadline(Duration.ofSeconds(5).toNanos())
            ),
            new CancellationException("graceful revocation")
        ));
        fixture.eventLoop.runUntilIdle();

        fixture.completeSource(8, "source-response");
        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();
        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();
        fixture.eventLoop.advance(Duration.ofSeconds(5));

        Assertions.assertEquals(
            java.util.List.of("turn:8", "processing:8", "cleanup-finished"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void gracefulCancellationPartwayThroughSendingAbortsAndEmitsOnlyCleanup() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(9, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(9);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.GracefulConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new org.opensearch.migrations.replay.identity.CancellationGrace.Revocation(
                new CancellationDeadline(Duration.ofSeconds(5).toNanos())
            ),
            new CancellationException("graceful revocation during request write")
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.attempt(0).abortCalls);
        Assertions.assertEquals(
            1,
            fixture.activePermits.get(),
            "the target-attempt permit remains held until asynchronous channel teardown completes"
        );
        Assertions.assertTrue(fixture.lifecycleEvents.isEmpty());

        fixture.targetChannel.attempt(0).abort.complete(null);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(
            java.util.List.of("cleanup-finished"),
            fixture.lifecycleEvents
        );
        assertOnlyCompletionDeliveryRemainsAtOwnerCleanup(fixture);
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void cancellationBeforeSendingProducesCleanupWithoutNormalRequestMilestones() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(7, Instant.EPOCH.plusSeconds(10));
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new CancellationException("cancel before preparation")
        ));
        fixture.eventLoop.runUntilIdle();
        fixture.eventLoop.advance(Duration.ofSeconds(20));

        Assertions.assertTrue(fixture.targetChannel.attempts.isEmpty());
        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(
            java.util.List.of("cleanup-finished"),
            fixture.lifecycleEvents
        );
        assertOnlyCompletionDeliveryRemainsAtOwnerCleanup(fixture);
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void abortedChannelTeardownCompletesBeforePermitReleaseOrReplacementAttempt() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(0, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(0);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.eventLoop.runUntilIdle();

        var cancellation = new CancellationException("generation cancelled");
        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            cancellation
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.attempt(0).abortCalls);
        Assertions.assertEquals(
            1,
            fixture.activePermits.get(),
            "the in-flight permit remains held while channel teardown is asynchronous"
        );

        var replacement = fixture.permitProvider.acquire(request(1));
        Assertions.assertFalse(replacement.completion().toCompletableFuture().isDone());

        fixture.targetChannel.attempt(0).abort.complete(null);
        fixture.eventLoop.runUntilIdle();

        var acquired = Assertions.assertInstanceOf(
            TargetAttemptPermitProvider.PermitAcquired.class,
            replacement.completion().toCompletableFuture().join()
        );
        Assertions.assertEquals(1, fixture.activePermits.get());
        acquired.permit().close();
        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(
            java.util.List.of("cleanup-finished"),
            fixture.lifecycleEvents,
            "cancellation emits no normal request milestone"
        );
        assertOnlyCompletionDeliveryRemainsAtOwnerCleanup(fixture);
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void rejectedOutcomePostingReleasesUndeliveredPermitAndReportsFatal() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(2, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(2);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.targetChannel.attempt(0).finalWrite();
        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(1, fixture.activePermits.get());

        fixture.eventLoop.rejectNewTasks();
        fixture.targetChannel.attempt(0).targetResponse("response");

        Assertions.assertEquals(
            0,
            fixture.activePermits.get(),
            "a typed outcome that cannot reach its owner must not leak its attempt permit"
        );
        Assertions.assertEquals(1, fixture.fatalFailures.size());
        Assertions.assertTrue(
            fixture.fatalFailures.get(0).getMessage().contains(
                "required event-loop submission target attempt outcome"
            )
        );
    }

    @Test
    void requiredReceiverCompletionRejectedByEventLoopRemainsRegisteredAndIsFatal() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        var turnAcceptance = new java.util.concurrent.CompletableFuture<Void>();
        fixture.lifecycleAcceptances.put("turn:3", turnAcceptance);
        fixture.admit(3, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(3);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        fixture.eventLoop.rejectNewTasks();
        turnAcceptance.complete(null);

        Assertions.assertFalse(
            fixture.owner.operations().snapshots().stream()
                .filter(snapshot -> snapshot.operationType() == OperationType.REQUIRED_DELIVERY)
                .toList()
                .isEmpty(),
            "the link is retained because its receiver completion was never applied"
        );
        Assertions.assertFalse(fixture.fatalFailures.isEmpty());
        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getMessage().contains("required event-loop submission")
            )
        );
    }

    private static void assertOnlyCompletionDeliveryRemainsAtOwnerCleanup(
        TargetConnectionOwnerTestSupport.Fixture fixture
    ) {
        var cleanupIndex = fixture.transitionHistory.indexOf(
            "lifecycle:cleanup-finished"
        );
        Assertions.assertTrue(cleanupIndex > 0);
        Assertions.assertEquals(
            "operations:1",
            fixture.transitionHistory.get(cleanupIndex - 1),
            "owner cleanup may be submitted only after every earlier operation registration is released"
        );
    }
}
