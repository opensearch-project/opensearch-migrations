/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RequestReplayOwnerTest {
    @Test
    void completeSourceBeforeTargetIsFrozenForRetryAndFinalTupleInput() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.retryPolicy.requiresSourceResponse = true;
        fixture.admit(0, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(0);
        fixture.eventLoop.runUntilIdle();

        fixture.completeSource(0, "complete-source");
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target");
        fixture.eventLoop.runUntilIdle();

        var retryInput = Assertions.assertInstanceOf(
            RequestReplayOwner.CompleteSourceResponseForRetry.class,
            fixture.retryPolicy.observedSources.get(0)
        );
        Assertions.assertEquals("complete-source", retryInput.response());
        Assertions.assertEquals(
            List.of("source-0|target|complete-source"),
            fixture.tupleSink.writes
        );

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(
            List.of("turn:0", "processing:0"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void unavailableRetryInputDoesNotPreventLaterCompleteFinalResponse() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.retryPolicy.requiresSourceResponse = true;
        fixture.admit(1, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(1);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target");
        fixture.eventLoop.runUntilIdle();

        fixture.unavailableForRetry(1);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            RequestReplayOwner.SourceResponseUnavailableForRetry.class,
            fixture.retryPolicy.observedSources.get(0)
        );
        Assertions.assertEquals(List.of("turn:1"), fixture.lifecycleEvents);
        Assertions.assertTrue(fixture.tupleSink.writes.isEmpty());

        fixture.completeFinalSource(1, "later-complete-source");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("source-1|target|later-complete-source"),
            fixture.tupleSink.writes
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void incompleteFinalResponseCarriesNoPartialResponseValue() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(2, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(2);
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target");
        fixture.incompleteSource(2);
        fixture.eventLoop.runUntilIdle();

        var finalResponse = fixture.tupleInputs.get(0).finalSourceResponse();
        var incomplete = Assertions.assertInstanceOf(
            RequestReplayOwner.IncompleteFinalSourceResponse.class,
            finalResponse
        );
        Assertions.assertEquals("expired", incomplete.reason());
        Assertions.assertEquals(
            List.of("source-2|target|incomplete:expired"),
            fixture.tupleSink.writes
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void duplicateFinalSourceResponseIsAnImpossibleTransition() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(3, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(3);
        fixture.eventLoop.runUntilIdle();

        fixture.completeSource(3, "first");
        fixture.eventLoop.runUntilIdle();
        fixture.completeFinalSource(3, "second");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertFalse(fixture.fatalFailures.isEmpty());
        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getCause().getMessage().contains(
                    "final source response was already supplied"
                )
            )
        );
    }

    @Test
    void duplicateRetrySourceResponseIsAnImpossibleTransition() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(7, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.RetrySourceResponseComplete<>(
            TargetConnectionOwnerTestSupport.CONNECTION,
            TargetConnectionOwnerTestSupport.GENERATION,
            TargetConnectionOwnerTestSupport.request(7),
            "first"
        ));
        fixture.eventLoop.runUntilIdle();
        fixture.owner.submit(new TargetConnectionOwner.RetrySourceResponseComplete<>(
            TargetConnectionOwnerTestSupport.CONNECTION,
            TargetConnectionOwnerTestSupport.GENERATION,
            TargetConnectionOwnerTestSupport.request(7),
            "second"
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getCause().getMessage().contains(
                    "retry source response was already supplied"
                )
            )
        );
    }

    @Test
    void responsePathStopsAfterTheConfiguredFourRetries() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.retryPolicy.decisions.addAll(List.of(
            new ReplayOutcomes.RetryDecision.RetryRequired(),
            new ReplayOutcomes.RetryDecision.RetryRequired(),
            new ReplayOutcomes.RetryDecision.RetryRequired(),
            new ReplayOutcomes.RetryDecision.RetryRequired(),
            new ReplayOutcomes.RetryDecision.RetryRequired()
        ));
        fixture.admit(6, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(6);
        fixture.completeSource(6, "source");
        fixture.eventLoop.runUntilIdle();

        for (int attempt = 0; attempt < 5; attempt++) {
            fixture.targetChannel.attempt(attempt).targetResponse("target-" + attempt);
            fixture.eventLoop.runUntilIdle();
            if (attempt < 4) {
                fixture.eventLoop.advance(java.time.Duration.ofSeconds(1));
            }
        }

        Assertions.assertEquals(5, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(5, fixture.retryPolicy.observedSources.size());
        Assertions.assertEquals(List.of("turn:6"), fixture.lifecycleEvents);
        Assertions.assertEquals(
            List.of("source-6|target-4|source"),
            fixture.tupleSink.writes
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void unexpectedPreparationExceptionReachesFatalBoundary() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(4, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();

        var failure = new IllegalStateException("transform failed");
        fixture.preparer.completions.get(
            TargetConnectionOwnerTestSupport.request(4)
        ).completeExceptionally(failure);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getCause() == failure
                    && error.getMessage().contains(
                        "request preparation exceptional completion"
                    )
            )
        );
    }

    @Test
    void sourceResponseRoutesOnlyToItsRegisteredRequestIdentity() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(5, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();

        var missing = fixture.owner.submit(
            new TargetConnectionOwner.FinalSourceResponseComplete<>(
                TargetConnectionOwnerTestSupport.CONNECTION,
                TargetConnectionOwnerTestSupport.GENERATION,
                TargetConnectionOwnerTestSupport.request(6),
                "wrong-request",
                true
            )
        );
        fixture.eventLoop.runUntilIdle();

        Assertions.assertThrows(
            CompletionException.class,
            () -> missing.toCompletableFuture().join()
        );
        Assertions.assertTrue(
            fixture.fatalFailures.stream().anyMatch(error ->
                error.getMessage().contains("source-response routing")
            )
        );
    }
}
