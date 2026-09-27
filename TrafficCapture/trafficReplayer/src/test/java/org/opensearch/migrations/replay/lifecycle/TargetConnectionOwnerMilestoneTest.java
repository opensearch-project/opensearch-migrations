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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetConnectionOwnerMilestoneTest {
    @Test
    void connectionTurnAdvancesBeforeTupleDurabilityAndRegistryWaitsForProcessingAcceptance() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        var processingAcceptance = new CompletableFuture<Void>();
        fixture.lifecycleAcceptances.put("processing:0", processingAcceptance);
        fixture.admit(0, Instant.EPOCH);
        fixture.admit(1, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(0);
        fixture.preparer.ready(1);
        fixture.eventLoop.runUntilIdle();

        fixture.targetChannel.attempt(0).targetResponse("target-0");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn:0"), fixture.lifecycleEvents);
        Assertions.assertEquals(2, fixture.targetChannel.attempts.size());
        Assertions.assertTrue(fixture.tupleSink.writes.isEmpty());
        Assertions.assertEquals(2, registeredRequests(fixture));

        fixture.completeSource(0, "source-response-0");
        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(
            List.of("source-0|target-0|source-response-0"),
            fixture.tupleSink.writes
        );
        Assertions.assertEquals(List.of("turn:0"), fixture.lifecycleEvents);

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("turn:0", "processing:0"),
            fixture.lifecycleEvents
        );
        Assertions.assertEquals(
            2,
            registeredRequests(fixture),
            "D17: processing acceptance, not turn completion or tuple durability, removes the request"
        );
        var requiredDelivery = fixture.owner.activitySnapshot()
            .stream()
            .filter(snapshot ->
                snapshot.operationType()
                    == OutstandingOperationRegistry.OperationType.REQUIRED_DELIVERY
            )
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(
            new KafkaRecordId(fixture.generation, 0),
            requiredDelivery.kafkaRecordId(),
            "request-specific required delivery must retain its Kafka record identity"
        );

        processingAcceptance.complete(null);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, registeredRequests(fixture));
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void permitAcquisitionSnapshotCarriesKafkaRecordIdentity() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture(1);
        var heldPermit = Assertions.assertInstanceOf(
            TargetAttemptPermitProvider.PermitAcquired.class,
            fixture.permitProvider.acquire(
                TargetConnectionOwnerTestSupport.request(99)
            ).completion().toCompletableFuture().join()
        );
        fixture.admit(7, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(7);
        fixture.eventLoop.runUntilIdle();

        var permitWait = fixture.owner.activitySnapshot()
            .stream()
            .filter(snapshot ->
                snapshot.operationType()
                    == OutstandingOperationRegistry.OperationType.PERMIT_ACQUISITION
            )
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(
            new KafkaRecordId(fixture.generation, 7),
            permitWait.kafkaRecordId(),
            "permit wait activity must identify the Kafka record that owns the request"
        );

        heldPermit.permit().close();
        fixture.eventLoop.runUntilIdle();
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void tupleAndProcessingWaitForBothTerminalTargetAndFinalSourceThenDurability() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(4, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(4);
        fixture.eventLoop.runUntilIdle();

        fixture.completeSource(4, "source-response");
        fixture.eventLoop.runUntilIdle();
        Assertions.assertTrue(fixture.tupleSink.writes.isEmpty());

        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn:4"), fixture.lifecycleEvents);
        Assertions.assertEquals(
            List.of("source-4|target-response|source-response"),
            fixture.tupleSink.writes
        );

        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("turn:4"), fixture.lifecycleEvents);

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("turn:4", "processing:4"),
            fixture.lifecycleEvents
        );
        Assertions.assertEquals(0, registeredRequests(fixture));
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void expectedTransformationFallbackRunsTargetAttemptAndTupleChain() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(5, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.fallback(5);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(1, fixture.activePermits.get());
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());

        fixture.completeSource(5, "source-response");
        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn:5"), fixture.lifecycleEvents);
        Assertions.assertEquals(
            List.of("source-5|target-response|source-response"),
            fixture.tupleSink.writes
        );
        Assertions.assertTrue(
            fixture.tupleInputs.getFirst().transformationStatus().isError()
        );

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("turn:5", "processing:5"),
            fixture.lifecycleEvents
        );
        Assertions.assertEquals(0, registeredRequests(fixture));
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void retrySourceWaitHoldsTurnWithoutPermitAndRetryReacquiresOne() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.retryPolicy.requiresSourceResponse = true;
        fixture.retryPolicy.decisions.add(new RetryDecision.RetryRequired());
        fixture.retryPolicy.decisions.add(
            new RetryDecision.TargetServerAttemptsFinished()
        );
        fixture.admit(9, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(9);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.activePermits.get());
        fixture.targetChannel.attempt(0).targetResponse("first-target");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());
        Assertions.assertTrue(fixture.lifecycleEvents.isEmpty());
        Assertions.assertTrue(
            fixture.retryPolicy.observedSources.isEmpty(),
            "retry policy must not run until its required source response is available"
        );

        fixture.completeSource(9, "source-response");
        fixture.eventLoop.runUntilIdle();
        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(1, fixture.retryPolicy.observedSources.size());
        Assertions.assertInstanceOf(
            RequestReplayOwner.CompleteSourceResponseForRetry.class,
            fixture.retryPolicy.observedSources.getFirst()
        );

        fixture.eventLoop.advance(Duration.ofMillis(999));
        Assertions.assertEquals(1, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(0, fixture.activePermits.get());

        fixture.eventLoop.advance(Duration.ofMillis(1));
        Assertions.assertEquals(2, fixture.targetChannel.attempts.size());
        Assertions.assertEquals(1, fixture.activePermits.get());

        fixture.targetChannel.attempt(1).targetResponse("second-target");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertEquals(List.of("turn:9"), fixture.lifecycleEvents);
        Assertions.assertEquals(
            List.of("source-9|second-target|source-response"),
            fixture.tupleSink.writes
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void filteredHeadUsesNoPermitOrExchangeAndStillTransformsItsSkippedTuple() {
        var transformations = new AtomicInteger();
        var fixture = new TargetConnectionOwnerTestSupport.Fixture(
            1,
            false,
            (replayContext, tuple) -> {
                transformations.incrementAndGet();
                return new org.opensearch.migrations.replay.sink.TupleWriter.TransformedTuple<>(
                    "transformed:" + tuple
                );
            }
        );
        fixture.admit(12, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.filtered(12);
        fixture.completeSource(12, "source-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertTrue(fixture.targetChannel.attempts.isEmpty());
        Assertions.assertEquals(List.of("turn:12"), fixture.lifecycleEvents);
        Assertions.assertEquals(1, transformations.get());
        Assertions.assertEquals(
            List.of("transformed:source-12|skipped|source-response"),
            fixture.tupleSink.writes
        );

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of("turn:12", "processing:12"), fixture.lifecycleEvents);
        Assertions.assertEquals(0, registeredRequests(fixture));
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void gracefulCancellationAllowsFilteredRequestToFinishItsTupleChain() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(13, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.filtered(13);
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.GracefulConnectionCancellation<>(
            TargetConnectionOwnerTestSupport.CONNECTION,
            TargetConnectionOwnerTestSupport.GENERATION,
            new org.opensearch.migrations.replay.identity.CancellationGrace.Revocation(
                new CancellationDeadline(Duration.ofSeconds(10).toNanos())
            ),
            new CancellationException("partition revoked")
        ));
        fixture.completeSource(13, "source-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(0, fixture.activePermits.get());
        Assertions.assertTrue(fixture.targetChannel.attempts.isEmpty());
        Assertions.assertEquals(List.of("turn:13"), fixture.lifecycleEvents);
        Assertions.assertEquals(
            List.of("source-13|skipped|source-response"),
            fixture.tupleSink.writes
        );

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("turn:13", "processing:13", "cleanup-finished"),
            fixture.lifecycleEvents
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void heartbeatExpirationFinishesAdmittedWorkThenClosesAndRemovesTheOwner() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(14, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(14);
        fixture.eventLoop.runUntilIdle();

        fixture.owner.submit(new TargetConnectionOwner.CapturedConnectionExpired<>(
            TargetConnectionOwnerTestSupport.CONNECTION,
            TargetConnectionOwnerTestSupport.GENERATION
        ));
        fixture.eventLoop.runUntilIdle();

        Assertions.assertTrue(
            fixture.targetChannel.closes.isEmpty(),
            "expiration must not interrupt an already-admitted target turn"
        );
        var rejectedAdmission = fixture.admit(15, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionRejected.class,
            rejectedAdmission.toCompletableFuture().join(),
            "expiration rejects later source admissions for this lifetime"
        );

        fixture.completeSource(14, "source-response");
        fixture.targetChannel.attempt(0).targetResponse("target-response");
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of(TargetConnectionOwnerTestSupport.CONNECTION),
            fixture.targetChannel.closes,
            "the target channel closes after the last admitted target turn"
        );
        Assertions.assertEquals(List.of("turn:14"), fixture.lifecycleEvents);

        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            List.of("turn:14", "processing:14", "owner-finished"),
            fixture.lifecycleEvents,
            "owner removal waits for durable request processing after target close"
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void targetInitiatedCloseBetweenRequestsReconnectsWithoutRemovingTheOwner() {
        var fixture = new TargetConnectionOwnerTestSupport.Fixture();
        fixture.admit(16, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(16);
        fixture.completeSource(16, "source-response-16");
        fixture.eventLoop.runUntilIdle();
        fixture.targetChannel.attempt(0).targetResponse("target-response-16");
        fixture.eventLoop.runUntilIdle();
        fixture.tupleSink.durableNext();
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fixture.targetChannel.connectionsOpened);
        Assertions.assertEquals(
            List.of("turn:16", "processing:16"),
            fixture.lifecycleEvents
        );

        fixture.targetChannel.targetInitiatedClose();
        fixture.admit(17, Instant.EPOCH);
        fixture.eventLoop.runUntilIdle();
        fixture.preparer.ready(17);
        fixture.eventLoop.runUntilIdle();

        Assertions.assertEquals(
            2,
            fixture.targetChannel.connectionsOpened,
            "the same owner asks its target port to reconnect for the later request"
        );
        Assertions.assertEquals(2, fixture.targetChannel.attempts.size());
        Assertions.assertFalse(
            fixture.lifecycleEvents.contains("owner-finished"),
            "a target-initiated close does not end the captured connection lifetime"
        );
        Assertions.assertTrue(fixture.fatalFailures.isEmpty());
    }

    @Test
    void manyConnectionsStayStickyAcrossSeveralLoopsAndOneStallDoesNotBlockAnother() {
        var clock = new FakeClock();
        var eventLoops = List.of(
            new TestEventLoop(clock),
            new TestEventLoop(clock),
            new TestEventLoop(clock)
        );
        var fixtures = new ArrayList<TargetConnectionOwnerTestSupport.Fixture>();
        for (var index = 0; index < 9; index++) {
            var generation = new PartitionGenerationId(
                new TopicPartition("traffic", index % 2),
                index + 1
            );
            var connection = new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("node", "connection-" + index),
                index
            );
            fixtures.add(new TargetConnectionOwnerTestSupport.Fixture(
                connection,
                clock,
                eventLoops.get(index % eventLoops.size())
            ));
        }

        for (var index = 0; index < fixtures.size(); index++) {
            fixtures.get(index).admit(index, Instant.EPOCH);
        }
        eventLoops.forEach(TestEventLoop::runUntilIdle);

        for (var index = 1; index < fixtures.size(); index++) {
            fixtures.get(index).preparer.ready(index);
        }
        eventLoops.forEach(TestEventLoop::runUntilIdle);

        Assertions.assertTrue(
            fixtures.getFirst().targetChannel.attempts.isEmpty(),
            "connection zero remains independently stalled in preparation"
        );
        for (var index = 1; index < fixtures.size(); index++) {
            Assertions.assertEquals(
                1,
                fixtures.get(index).targetChannel.attempts.size(),
                "connection " + index + " must progress on its assigned loop"
            );
        }
        Assertions.assertEquals(
            1,
            fixtures.get(3).targetChannel.attempts.size(),
            "an unrelated owner sharing the stalled connection's loop still progresses"
        );

        var sameOwnerSecondAdmission = fixtures.get(4).admit(104, Instant.EPOCH);
        eventLoops.get(0).runUntilIdle();
        eventLoops.get(2).runUntilIdle();
        Assertions.assertFalse(
            sameOwnerSecondAdmission.toCompletableFuture().isDone(),
            "another loop cannot apply an admission owned by connection four"
        );
        eventLoops.get(1).runUntilIdle();
        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionAccepted.class,
            sameOwnerSecondAdmission.toCompletableFuture().join(),
            "later work for a connection remains sticky to its original event loop"
        );
        Assertions.assertTrue(
            fixtures.stream().allMatch(fixture -> fixture.fatalFailures.isEmpty())
        );
    }

    private static int registeredRequests(TargetConnectionOwnerTestSupport.Fixture fixture) {
        var count = new AtomicInteger(-1);
        fixture.eventLoop.execute(() -> count.set(fixture.owner.registeredRequestCount()));
        fixture.eventLoop.runUntilIdle();
        return count.get();
    }
}
