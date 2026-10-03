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
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestAdmissionAccepted;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestAdmissionRejected;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.CONNECTION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.GENERATION;

class TargetConnectionOwnerAdmissionTest {
    @Test
    void admissionResultContainsOnlyAcceptedAndExpectedCancellationRejection() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.owner.submit(new TargetConnectionOwner.ForceConnectionCancellation<>(
            CONNECTION,
            GENERATION,
            new java.util.concurrent.CancellationException("generation cancelled")
        ));
        fixture.eventLoop.runUntilIdle();

        var result = fixture.admit(1, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            RequestAdmissionRejected.class,
            result.toCompletableFuture().join()
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void duplicateAdmissionIsFatalRatherThanExpectedRejection() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(2, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();

        var duplicate = fixture.admit(2, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertThrows(
            CompletionException.class,
            () -> duplicate.toCompletableFuture().join()
        );
        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getMessage().contains("AdmitReconstitutedRequest")
                    && error.getCause().getMessage().contains("already registered")
            )
        );
    }

    @Test
    void preparationUsesFirstByteTimingAndOutOfOrderReadinessCannotOvertake() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        var firstAdmission = fixture.admit(7, Instant.EPOCH.plusSeconds(10));
        var secondAdmission = fixture.admit(8, Instant.EPOCH.plusSeconds(11));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            RequestAdmissionAccepted.class,
            firstAdmission.toCompletableFuture().join()
        );
        Assertions.assertInstanceOf(
            RequestAdmissionAccepted.class,
            secondAdmission.toCompletableFuture().join()
        );

        fixture.eventLoop.advance(Duration.ofSeconds(9));
        Assertions.assertEquals(List.of(TargetConnectionOwnerTestSupport.request(7)),
            fixture.preparer.begun);
        Assertions.assertEquals(List.of(Instant.EPOCH.plusSeconds(9)),
            fixture.preparer.beginTimes);

        fixture.eventLoop.advance(Duration.ofSeconds(1));
        fixture.preparer.ready(8);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of(
                TargetConnectionOwnerTestSupport.request(7),
                TargetConnectionOwnerTestSupport.request(8)
            ),
            fixture.preparer.begun
        );
        Assertions.assertEquals(
            List.of(Instant.EPOCH.plusSeconds(9), Instant.EPOCH.plusSeconds(10)),
            fixture.preparer.beginTimes
        );
        Assertions.assertTrue(fixture.targetChannel.attempts.isEmpty());
        Assertions.assertEquals(0, fixture.activePermits.get());

        fixture.preparer.ready(7);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(
            TargetConnectionOwnerTestSupport.request(7),
            fixture.targetChannel.attempt(0).input.requestId()
        );
        Assertions.assertEquals(1, fixture.activePermits.get());

        fixture.targetChannel.attempt(0).targetResponse("first");
        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());

        fixture.eventLoop.advance(Duration.ofSeconds(1));
        Assertions.assertEquals(2, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(
            TargetConnectionOwnerTestSupport.request(8),
            fixture.targetChannel.attempt(1).input.requestId()
        );
        Assertions.assertEquals(1, fixture.activePermits.get());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void preparationThatMissesNominalTimeRunsImmediatelyWhenReady() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(3, Instant.EPOCH.plusSeconds(2));
        fixture.eventLoop.runUntilIdle();

        fixture.eventLoop.advance(Duration.ofSeconds(1));
        Assertions.assertEquals(
            List.of(Instant.EPOCH.plusSeconds(1)),
            fixture.preparer.beginTimes
        );
        fixture.eventLoop.advance(Duration.ofSeconds(4));
        Assertions.assertTrue(fixture.targetChannel.attempts.isEmpty());

        fixture.preparer.ready(3);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(Instant.EPOCH.plusSeconds(5), fixture.clock.instant());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void capturedCloseRemainsBehindEarlierRequestTurn() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(40, Instant.EPOCH);
        fixture.owner.submit(new TargetConnectionOwner.AdmitCapturedClose<>(
            CONNECTION,
            GENERATION,
            41,
            Instant.EPOCH
        ));
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(40);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());
        Assertions.assertTrue(fixture.targetChannel.closes.isEmpty());

        fixture.targetChannel.attempt(0).targetResponse("response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of(CONNECTION), fixture.targetChannel.closes);
        Assertions.assertEquals(List.of("turn:40"), fixture.lifecycleEvents);
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }
}
