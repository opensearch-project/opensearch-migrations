/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetConnectionOwnerFirstWriteTest {
    @Test
    void firstAndFinalWriteMilestonesStayLocalAndRetriesDoNotRepeatThem() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.retryPolicy.decisions.add(new RetryDecision.RetryRequired());
        fixture.retryPolicy.decisions.add(
            new RetryDecision.TargetServerAttemptsFinished()
        );
        fixture.admit(0, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(0);
        fixture.eventLoop.runUntilIdle();

        fixture.targetChannel.attempt(0).firstWrite();
        fixture.targetChannel.attempt(0).finalWrite();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(
            fixture.lifecycleEvents.isEmpty(),
            "write milestones are connection-local cancellation state"
        );

        fixture.targetChannel.attempt(0).targetResponse("retryable");
        fixture.eventLoop.runUntilIdle();
        fixture.eventLoop.advance(Duration.ofSeconds(1));

        fixture.targetChannel.attempt(1).firstWrite();
        fixture.targetChannel.attempt(1).finalWrite();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(fixture.lifecycleEvents.isEmpty());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());

        fixture.targetChannel.attempt(1).targetResponse("terminal");
        fixture.completeSource(0, "source");
        fixture.eventLoop.runUntilIdle();
        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("turn:0", "processing:0"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void duplicateMilestoneFromSameAttemptIsFatal() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(1, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(1);
        fixture.eventLoop.runUntilIdle();

        fixture.targetChannel.attempt(0).firstWrite();
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.fatalFailures.size());
        Assertions.assertTrue(
            fixture.fatalFailures.get(0).getCause().getMessage().contains(
                "first target write was submitted more than once"
            )
        );
    }

    @Test
    void targetResponseBeforeFinalWriteIsImpossible() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(11, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(11);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).firstWrite();
        fixture.eventLoop.runUntilIdle();

        fixture.targetChannel.attempt(0).outcome.complete(
            new TargetAttemptOutcome.TargetResponseObtained<>("response")
        );
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getCause().getMessage().contains(
                    "FinalTargetWriteSubmitted"
                )
            )
        );
    }

    @Test
    void noResponseIsTypedAtChannelBoundaryAndRetriesWithoutExceptionalControlFlow() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(2, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(2);
        fixture.eventLoop.runUntilIdle();

        var firstAttempt = fixture.targetChannel.attempt(0);
        firstAttempt.noResponse();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            TargetAttemptOutcome.NoTargetResponseObtained.class,
            firstAttempt.outcome.toCompletableFuture().join()
        );
        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertTrue(fixture.lifecycleEvents.isEmpty());

        fixture.eventLoop.advance(Duration.ofSeconds(1));
        for (int attempt = 1; attempt < 5; attempt++) {
            fixture.targetChannel.attempt(attempt).noResponse();
            fixture.eventLoop.runUntilIdle();
            fixture.eventLoop.advance(Duration.ofSeconds(1));
        }

        Assertions.assertEquals(
            6,
            fixture.targetChannel.attempts.size(),
            "the response-only cap must not limit the no-response retry path"
        );
        Assertions.assertEquals(1, fixture.activePermits.get());
        Assertions.assertTrue(fixture.lifecycleEvents.isEmpty());

        var cancellation = new java.util.concurrent.CancellationException("cancel retry loop");
        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            TargetConnectionOwnerTestSupport.CONNECTION,
            TargetConnectionOwnerTestSupport.GENERATION,
            cancellation
        ));
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(5).abort.complete(null);
        fixture.eventLoop.runUntilIdle();
        fixture.eventLoop.advance(Duration.ofSeconds(10));

        Assertions.assertEquals(6, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }
}
