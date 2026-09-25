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

import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.CONNECTION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.GENERATION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.request;

class TargetConnectionOwnerCancellationTest {
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
            java.util.List.of("owner-finished"),
            fixture.lifecycleEvents
        );
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
            java.util.List.of("owner-finished"),
            fixture.lifecycleEvents,
            "cancellation emits no normal request milestone"
        );
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
}
