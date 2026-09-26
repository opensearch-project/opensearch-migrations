/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.ReplayIntakeMetrics;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.InterimResponseObservation;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Record accounting across source assembly.
 *
 * <p>Covers {@code kafkaLLD §8.3}'s mixed record and {@code §9.2}'s "the request's record associations remain
 * until {@code RequestProcessingFinished}". The tests observe only typed lifecycle inputs, emitted record
 * completions, and conservation metrics, so early, missing, duplicate, or hidden work changes their results.
 */
class RecordAssociationAccumulatorTest {

    private static final String TOPIC = "traffic";
    private static final String WRITER = "writer";
    private static final String CONNECTION = "connection";
    private static final Timestamp OBSERVATION_TIME = Timestamp.newBuilder().setSeconds(1).build();

    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(false, true);
    private final RootReplayerContext rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
    private final WakeupController wakeupController =
        new WakeupController(() -> {}, rootContext);
    private final KafkaSourceInputQueue sourceInputs = new KafkaSourceInputQueue(wakeupController);
    private final ReplayIntakeInputQueue intakeInputs = new ReplayIntakeInputQueue();
    private final RecordingSink sink = new RecordingSink();
    private final ReplayIntakeOwner owner = new ReplayIntakeOwner(
        intakeInputs,
        sourceInputs,
        sink,
        failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
        rootContext.replayIntakeMetrics
    );

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
    }

    /**
     * Plan A's G3 exit case: one record carrying {@code read+EOM} for request <em>N</em> and {@code read} for
     * request <em>N+1</em> holds exactly both associations.
     *
     * <p>{@code §8.3}: "A record can contain associations with several request identities.
     * {@code RequestProcessingFinished} for one request removes only that request's association from each
     * contributing record." So finishing request 0 must leave this record held by request 1's assembly.
     */
    @Test
    void mixedKeepAliveRecordWaitsForBothLifecycleResults() {
        var firstStream = stream(
            0,
            read(1, "GET /0 HTTP/1.1\r\n\r\n"),
            endOfMessage(2),
            write(3, "HTTP/1.1 200 OK\r\n\r\n"),
            read(4, "GET /1 HTTP/1.1\r\n\r\n")
        );
        var secondStream = stream(1, endOfMessage(5), close(6));
        var script = new RecordScript(TOPIC)
            .addTraffic(
                0,
                0,
                Instant.ofEpochMilli(1_000),
                WRITER,
                firstStream
            )
            .addTraffic(
                0,
                1,
                Instant.ofEpochMilli(2_000),
                WRITER,
                secondStream
            );
        var generation = script.generation(0);
        var firstRecord = script.records().get(0).recordId();
        var secondRecord = script.records().get(1).recordId();

        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 0),
            List.of(script.records().get(0))
        ));
        Assertions.assertTrue(sourceCompletions().isEmpty());

        finishRequest(script, 0);
        Assertions.assertTrue(
            sourceCompletions().isEmpty(),
            "request N+1 assembly must continue to hold the mixed record"
        );

        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 1),
            List.of(script.records().get(1))
        ));
        Assertions.assertTrue(
            sourceCompletions().isEmpty(),
            "relabeling request N+1 must preserve the mixed record's outstanding work"
        );

        finishRequest(script, 1);

        var completions = sourceCompletions();
        Assertions.assertEquals(2, completions.size(), "each record must finish exactly once");
        Assertions.assertEquals(
            Set.of(firstRecord, secondRecord),
            Set.copyOf(completions),
            "the two records finish only after both typed request-processing results arrive"
        );
        var metrics = telemetry.getFinishedMetrics();
        Assertions.assertTrue(
            metrics.stream().anyMatch(metric ->
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS.equals(metric.getName())),
            "the active-tracker instrument must exist before its balanced value can be evidence"
        );
        Assertions.assertEquals(
            0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                metrics,
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            ),
            "finishing every expected lifecycle must leave no hidden association holding either record"
        );
    }

    /**
     * {@code §9.2}: "bytes remain in replay intake until the response is complete" and "the request's record
     * associations remain until {@code RequestProcessingFinished}".
     *
     * <p>The second record carries no part of the request — only response bytes — and is still held by it.
     */
    @Test
    void sourceResponseRecordRemainsAssociatedUntilRequestProcessingFinishes() {
        var first = stream(0, read(1, "GET / HTTP/1.1\r\n\r\n"), endOfMessage(2));
        var response = stream(
            1,
            write(3, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"),
            close(4)
        );
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER, first)
            .addTraffic(0, 1, Instant.ofEpochMilli(2_000), WRITER, response);
        assignAndApply(script);

        Assertions.assertTrue(sourceCompletions().isEmpty());
        Assertions.assertEquals(
            2,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            ),
            "both request and response records remain owned until processing completion"
        );
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.RETRY_READY_REQUEST_SUPPLY
            ),
            "the complete source response makes the unfinished target turn retry-ready supply"
        );
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.RETRY_READY_SUPPLY_ADDITIONS
            )
        );
        finishRequest(script, 0);

        Assertions.assertEquals(
            List.of(
                new KafkaRecordId(script.generation(0), 0),
                new KafkaRecordId(script.generation(0), 1)
            ),
            sourceCompletions(),
            "both records finish together, and only once the request's processing is complete"
        );
        Assertions.assertEquals(
            0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.RETRY_READY_REQUEST_SUPPLY
            ),
            "the finished target turn leaves supply exactly once"
        );
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.RETRY_READY_SUPPLY_REMOVALS
            )
        );
    }

    @Test
    void interimOnlyRecordIsRelabeledAndHeldUntilRequestProcessingFinishes() {
        var request = stream(0, read(1, "GET / HTTP/1.1\r\n\r\n"));
        var interim = stream(1, interim(2, "HTTP/1.1 103 Early Hints\r\n\r\n"));
        var completion = stream(
            2,
            endOfMessage(3),
            write(4, "HTTP/1.1 200 OK\r\n\r\n"),
            close(5)
        );
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER, request)
            .addTraffic(0, 1, Instant.ofEpochMilli(2_000), WRITER, interim)
            .addTraffic(0, 2, Instant.ofEpochMilli(3_000), WRITER, completion);
        assignAndApply(script);

        Assertions.assertTrue(
            sourceCompletions().isEmpty(),
            "the interim-only record must retain the request assembly association through relabeling"
        );
        Assertions.assertEquals(
            3,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            )
        );

        finishRequest(script, 0);

        var completions = sourceCompletions();
        Assertions.assertEquals(3, completions.size(), "each contributing record must finish exactly once");
        Assertions.assertEquals(
            Set.of(
                new KafkaRecordId(script.generation(0), 0),
                new KafkaRecordId(script.generation(0), 1),
                new KafkaRecordId(script.generation(0), 2)
            ),
            Set.copyOf(completions)
        );
    }

    @Test
    void earlyFinalResponseRecordIsRelabeledAndHeldUntilRequestProcessingFinishes() {
        var requestPrefix = stream(0, read(1, "POST / HTTP/1.1\r\nContent-Length: 1\r\n\r\n"));
        var earlyFinal = stream(1, write(2, "HTTP/1.1 413 Content Too Large\r\n\r\n"));
        var completion = stream(2, read(3, "x"), endOfMessage(4), close(5));
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER, requestPrefix)
            .addTraffic(0, 1, Instant.ofEpochMilli(2_000), WRITER, earlyFinal)
            .addTraffic(0, 2, Instant.ofEpochMilli(3_000), WRITER, completion);
        assignAndApply(script);

        Assertions.assertTrue(
            sourceCompletions().isEmpty(),
            "the early-final record must retain the request assembly association through relabeling"
        );
        Assertions.assertEquals(
            3,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            )
        );

        finishRequest(script, 0);

        var completions = sourceCompletions();
        Assertions.assertEquals(3, completions.size(), "each contributing record must finish exactly once");
        Assertions.assertEquals(
            Set.of(
                new KafkaRecordId(script.generation(0), 0),
                new KafkaRecordId(script.generation(0), 1),
                new KafkaRecordId(script.generation(0), 2)
            ),
            Set.copyOf(completions)
        );
    }

    /**
     * {@code §17.1}: "A heartbeat-only or probe-only record completes immediately after application."
     *
     * <p>Neither payload creates connection state, so the record closes with no associations and {@code §8.3}'s
     * predicate is satisfied at step 9 of the same application.
     */
    @Test
    void aHeartbeatOrProbeRecordCompletesWithinItsOwnApplication() {
        var script = new RecordScript(TOPIC)
            .addHeartbeat(0, 0, Instant.ofEpochMilli(1_000), WRITER, 1_000L)
            .addProbe(0, 1, Instant.ofEpochMilli(2_000), WRITER, "probe-1");
        assignAndApply(script);

        Assertions.assertEquals(
            List.of(
                new KafkaRecordId(script.generation(0), 0),
                new KafkaRecordId(script.generation(0), 1)
            ),
            sourceCompletions(),
            "a record that creates no work is finished by the application that registered it"
        );
        var metrics = telemetry.getFinishedMetrics();
        Assertions.assertEquals(
            2,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                metrics,
                ReplayIntakeMetrics.MetricNames.RECORD_TRACKERS_RETIRED
            ),
            "both accepted source-queue submissions retire their record trackers"
        );
        Assertions.assertTrue(
            metrics.stream().anyMatch(metric ->
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS.equals(metric.getName())),
            "the active-tracker instrument must be present even when its balanced value is zero"
        );
        Assertions.assertEquals(
            0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                metrics,
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            ),
            "tracker retirement is observable without exposing the partition's tracker map"
        );
    }

    /**
     * {@code §7.1}: {@code PAYLOAD_NOT_SET} is a protocol violation, and {@code §16} sends it to the Kafka
     * source rather than finishing the record.
     */
    @Test
    void anEnvelopeWithNoPayloadIsAProtocolViolationRatherThanACompletedRecord() {
        var script = new RecordScript(TOPIC).addPayloadNotSet(0, 0, Instant.ofEpochMilli(1_000), WRITER);
        assignAndApply(script);

        var sourceEvents = drainSourceInputs();
        var violations = sourceEvents.stream()
            .filter(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
            .toList();
        Assertions.assertEquals(1, violations.size(), "the violation must reach the source");
        Assertions.assertTrue(
            sourceEvents.stream().noneMatch(KafkaSourceInput.RecordProcessingFinished.class::isInstance),
            "the violating record must remain unfinished"
        );
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            ),
            "the violating record must remain tracked without exposing the tracker map to the test"
        );
    }

    /** {@code §16}: only work admitted before a violation may drain; a later record is not applied. */
    @Test
    void aRecordAfterAProtocolViolationIsNotApplied() {
        var script = new RecordScript(TOPIC)
            .addPayloadNotSet(0, 0, Instant.ofEpochMilli(1_000), WRITER)
            .addTraffic(
                0,
                1,
                Instant.ofEpochMilli(2_000),
                WRITER,
                stream(0, read(1, "GET /must-not-run HTTP/1.1\r\n\r\n"), endOfMessage(2))
            );

        assignAndApply(script);

        Assertions.assertTrue(sink.reconstituted.isEmpty(), "no request after the violating offset may pass");
    }

    /** {@code §16}: a later, separately delivered batch is rejected after the generation's first violation. */
    @Test
    void aBatchSubmittedAfterAProtocolViolationIsNotApplied() {
        var script = new RecordScript(TOPIC)
            .addPayloadNotSet(0, 0, Instant.ofEpochMilli(1_000), WRITER)
            .addTraffic(
                0,
                1,
                Instant.ofEpochMilli(2_000),
                WRITER,
                stream(0, read(1, "GET /later-batch-must-not-run HTTP/1.1\r\n\r\n"), endOfMessage(2))
            );
        var generation = script.generation(0);

        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 0),
            List.of(script.records().get(0))
        ));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 1),
            List.of(script.records().get(1))
        ));

        Assertions.assertTrue(
            sink.reconstituted.isEmpty(),
            "a new batch must not reopen record admission after a capture-protocol violation"
        );
    }

    /**
     * {@code §16}: the poison record terminates the replay, not only its partition. A batch already queued
     * for another assigned partition cannot admit work after the replay-wide cutoff is latched.
     */
    @Test
    void aProtocolViolationRejectsLaterBatchesFromEveryPartition() {
        var violating = new RecordScript(TOPIC)
            .addPayloadNotSet(0, 0, Instant.ofEpochMilli(1_000), WRITER);
        var otherPartition = new RecordScript(TOPIC)
            .addTraffic(
                1,
                0,
                Instant.ofEpochMilli(2_000),
                WRITER,
                stream(0, read(1, "GET /must-not-run HTTP/1.1\r\n\r\n"), endOfMessage(2))
            );

        owner.applyOnCallingThread(
            new ReplayIntakeInput.PartitionGenerationAssigned(violating.generation(0))
        );
        owner.applyOnCallingThread(
            new ReplayIntakeInput.PartitionGenerationAssigned(otherPartition.generation(1))
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(violating.generation(0), 0),
            violating.records()
        ));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(otherPartition.generation(1), 0),
            otherPartition.records()
        ));

        Assertions.assertTrue(
            sink.reconstituted.isEmpty(),
            "a capture-protocol violation must stop record admission across the whole replay"
        );
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.RECORD_BATCHES_REJECTED_AFTER_PROTOCOL_VIOLATION
            ),
            "the replay-wide cutoff must be observable when it rejects another partition's batch"
        );
    }

    /**
     * Refactors the stale-accumulation and interrupted-close regressions onto the typed generation boundary.
     * Old assembly is removed before a successor can apply the same captured connection identity.
     */
    @Test
    void cancelledGenerationReleasesStaleAssemblyAndBalancesItsRecordTrackersBeforeSuccessorInput() {
        var predecessor = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(0, read(1, "POST /stale HTTP/1.1\r\nContent-Length: 1\r\n\r\n"))
        );
        var predecessorGeneration = predecessor.generation(0);
        owner.applyOnCallingThread(
            new ReplayIntakeInput.PartitionGenerationAssigned(predecessorGeneration)
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(predecessorGeneration, 0),
            predecessor.records()
        ));
        Assertions.assertEquals(
            1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            )
        );

        owner.applyOnCallingThread(new ReplayIntakeInput.GracefulGenerationCancellation(
            predecessorGeneration,
            new CancellationGrace.Revocation(new CancellationDeadline(Duration.ofSeconds(1).toNanos()))
        ));

        var afterCleanup = drainSourceInputs();
        Assertions.assertEquals(
            1,
            afterCleanup.stream()
                .filter(KafkaSourceInput.GenerationCleanupFinished.class::isInstance)
                .count(),
            "a generation with no target owner must finish cleanup immediately"
        );
        Assertions.assertTrue(
            afterCleanup.stream().noneMatch(KafkaSourceInput.RecordProcessingFinished.class::isInstance),
            "discarding cancelled assembly must not manufacture commit authority"
        );
        Assertions.assertEquals(
            0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            ),
            "cleanup must return the process-wide tracker gauge to its prior value"
        );

        var successor = new RecordScript(TOPIC, 1).addTraffic(
            0,
            1,
            Instant.ofEpochMilli(2_000),
            WRITER,
            stream(
                0,
                read(1, "GET /successor HTTP/1.1\r\n\r\n"),
                endOfMessage(2)
            )
        );
        owner.applyOnCallingThread(
            new ReplayIntakeInput.PartitionGenerationAssigned(successor.generation(0))
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(successor.generation(0), 0),
            successor.records()
        ));

        Assertions.assertEquals(
            1,
            sink.reconstituted.size(),
            "the successor must reconstruct freshly instead of finding predecessor assembly"
        );
        Assertions.assertEquals(
            successor.generation(0),
            sink.reconstituted.get(0).connectionProcessingId().generation()
        );
    }

    @Test
    void cleanupAcknowledgementIsGenerationScopedIdempotentAndCannotFinishKafkaRecords() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(0, read(1, "GET / HTTP/1.1\r\n\r\n"), endOfMessage(2))
        );
        assignAndApply(script);
        var generation = script.generation(0);
        var connection = sink.reconstituted.get(0).connectionProcessingId();
        Assertions.assertTrue(
            owner.partitionState(generation).orElseThrow()
                .lifetimeOf(connection).orElseThrow()
                .hasConnectionOwner()
        );

        owner.applyOnCallingThread(new ReplayIntakeInput.GracefulGenerationCancellation(
            generation,
            new CancellationGrace.Revocation(new CancellationDeadline(Duration.ofSeconds(1).toNanos()))
        ));
        Assertions.assertEquals(List.of(connection), sink.gracefulCancellations);
        Assertions.assertTrue(
            drainSourceInputs().stream()
                .noneMatch(KafkaSourceInput.GenerationCleanupFinished.class::isInstance),
            "cleanup must wait for the published connection owner"
        );

        owner.applyOnCallingThread(new ReplayIntakeInput.ForceGenerationCancellation(generation));
        owner.applyOnCallingThread(new ReplayIntakeInput.ForceGenerationCancellation(generation));
        Assertions.assertEquals(
            List.of(connection),
            sink.forceCancellations,
            "duplicate force delivery must be inert while owner cleanup is pending"
        );

        var unrelatedGeneration = new org.opensearch.migrations.replay.identity.PartitionGenerationId(
            generation.topicPartition(),
            generation.localSequence() + 1
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.ConnectionCleanupFinished(
            unrelatedGeneration,
            connection
        ));
        Assertions.assertTrue(
            drainSourceInputs().stream()
                .noneMatch(KafkaSourceInput.GenerationCleanupFinished.class::isInstance),
            "an acknowledgement under another generation cannot settle the predecessor"
        );

        owner.applyOnCallingThread(new ReplayIntakeInput.ConnectionCleanupFinished(
            generation,
            connection
        ));
        owner.applyOnCallingThread(new ReplayIntakeInput.ConnectionCleanupFinished(
            generation,
            connection
        ));

        var afterExactAcknowledgement = drainSourceInputs();
        Assertions.assertEquals(
            1,
            afterExactAcknowledgement.stream()
                .filter(KafkaSourceInput.GenerationCleanupFinished.class::isInstance)
                .count(),
            "the exact owner acknowledgement completes its generation once"
        );
        Assertions.assertTrue(
            afterExactAcknowledgement.stream()
                .noneMatch(KafkaSourceInput.RecordProcessingFinished.class::isInstance),
            "typed cleanup never authorizes commit"
        );
        Assertions.assertEquals(
            0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                ReplayIntakeMetrics.MetricNames.ACTIVE_RECORD_TRACKERS
            )
        );
    }

    @Test
    void shutdownGraceUsesOrdinaryConnectionCompletionAndNeverForcesTheGeneration() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(0, read(1, "GET / HTTP/1.1\r\n\r\n"), endOfMessage(2))
        );
        assignAndApply(script);
        var generation = script.generation(0);
        var connection = sink.reconstituted.get(0).connectionProcessingId();
        Assertions.assertTrue(
            owner.partitionState(generation).orElseThrow()
                .lifetimeOf(connection).orElseThrow()
                .hasConnectionOwner()
        );

        owner.applyOnCallingThread(new ReplayIntakeInput.GracefulGenerationCancellation(
            generation,
            CancellationGrace.Shutdown.INSTANCE
        ));
        Assertions.assertEquals(List.of(connection), sink.gracefulCancellations);
        Assertions.assertEquals(List.of(CancellationGrace.Shutdown.INSTANCE), sink.graceModes);
        Assertions.assertTrue(sink.forceCancellations.isEmpty());

        owner.applyOnCallingThread(new ReplayIntakeInput.ConnectionOwnerFinished(
            generation,
            connection
        ));

        Assertions.assertEquals(
            1,
            drainSourceInputs().stream()
                .filter(KafkaSourceInput.GenerationCleanupFinished.class::isInstance)
                .count()
        );
        Assertions.assertTrue(sink.forceCancellations.isEmpty());
    }

    @Test
    void activeGenerationRejectsDuplicateAndImpossibleLifecycleInputs() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(0, read(1, "GET / HTTP/1.1\r\n\r\n"), endOfMessage(2))
        );
        assignAndApply(script);
        var generation = script.generation(0);
        var connection = sink.reconstituted.get(0).connectionProcessingId();
        var requestId = new ReplayRequestId(connection, 0);

        owner.applyOnCallingThread(new RequestLifecycleInput.ConnectionRequestFinished(
            generation,
            requestId
        ));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.applyOnCallingThread(new RequestLifecycleInput.ConnectionRequestFinished(
                generation,
                requestId
            )),
            "an active generation must reject duplicate target-turn completion"
        );

        var unknownRequest = new ReplayRequestId(connection, 99);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.applyOnCallingThread(new RequestLifecycleInput.RequestProcessingFinished(
                generation,
                unknownRequest
            )),
            "an active generation must reject processing completion for an unknown request"
        );
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.applyOnCallingThread(new ReplayIntakeInput.ConnectionCleanupFinished(
                generation,
                connection
            )),
            "cancellation cleanup is impossible while a generation remains active"
        );

        var mismatchedGeneration = new org.opensearch.migrations.replay.identity.PartitionGenerationId(
            generation.topicPartition(),
            generation.localSequence() + 1
        );
        var mismatchedConnection = new ConnectionProcessingId(
            mismatchedGeneration,
            connection.capturedConnectionId(),
            connection.localSequence()
        );
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.applyOnCallingThread(new ReplayIntakeInput.ConnectionOwnerFinished(
                generation,
                mismatchedConnection
            )),
            "an active generation must reject a connection-owner completion carrying another generation"
        );
    }

    /**
     * {@code procCommit §6.1}: a close-only record finishes once source assembly settles it. {@code §9.3}
     * says no connection owner is created merely to process that close when no request was reconstituted.
     */
    @Test
    void aCloseOnlyRecordFinishesWithoutCreatingAConnectionOwner() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(0, close(1))
        );

        assignAndApply(script);

        Assertions.assertTrue(
            sink.closes.isEmpty(),
            "source-side settlement is sufficient when no reconstituted request created a connection owner"
        );
        Assertions.assertEquals(
            List.of(script.records().get(0).recordId()),
            sourceCompletions(),
            "the terminal association must be released after acceptance"
        );
    }

    // ---------------------------------------------------------------- driving

    private void assignAndApply(RecordScript script) {
        var generation = script.generation(0);
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 0),
            script.records()
        ));
        while (script.hasNext()) {
            script.next();
        }
    }

    private void finishRequest(RecordScript script, long capturedRequestOrdinal) {
        var requestId = new ReplayRequestId(lifetimeOf(script), capturedRequestOrdinal);
        owner.applyOnCallingThread(new RequestLifecycleInput.ConnectionRequestFinished(
            script.generation(0),
            requestId
        ));
        owner.applyOnCallingThread(new RequestLifecycleInput.RequestProcessingFinished(
            script.generation(0),
            requestId
        ));
    }

    /**
     * The one connection lifetime these scripts create. Read from the sink rather than reconstructed, so the
     * test cannot disagree with production about which lifetime the requests belong to.
     */
    private ConnectionProcessingId lifetimeOf(RecordScript script) {
        return sink.reconstituted.isEmpty()
            ? new ConnectionProcessingId(
                script.generation(0),
                new org.opensearch.migrations.replay.identity.CapturedConnectionId(WRITER, CONNECTION),
                0
            )
            : sink.reconstituted.get(0).connectionProcessingId();
    }

    private List<KafkaRecordId> sourceCompletions() {
        return drainSourceInputs().stream()
            .filter(KafkaSourceInput.RecordProcessingFinished.class::isInstance)
            .map(input -> ((KafkaSourceInput.RecordProcessingFinished) input).recordId())
            .toList();
    }

    private final List<KafkaSourceInput> drainedSourceInputs = new ArrayList<>();

    private List<KafkaSourceInput> drainSourceInputs() {
        drainedSourceInputs.addAll(sourceInputs.drain());
        return List.copyOf(drainedSourceInputs);
    }

    // ---------------------------------------------------------------- fixtures

    /** Records what source assembly emitted, so a test can read the lifetime it allocated. */
    private static final class RecordingSink implements SourceAssemblySink {
        private final List<ReplayRequestId> reconstituted = new ArrayList<>();
        private final List<ReplayRequestId> completeResponses = new ArrayList<>();
        private final List<ReplayRequestId> incompleteResponses = new ArrayList<>();
        private final List<ConnectionProcessingId> closes = new ArrayList<>();
        private final List<ConnectionProcessingId> gracefulCancellations = new ArrayList<>();
        private final List<CancellationGrace> graceModes = new ArrayList<>();
        private final List<ConnectionProcessingId> forceCancellations = new ArrayList<>();

        @Override
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant requestFirstByteSourceTime,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime
        ) {
            reconstituted.add(replayRequestId);
        }

        @Override
        public void onSourceInterimResponse(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.InterimResponse interimResponse
        ) {}

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            completeResponses.add(replayRequestId);
        }

        @Override
        public void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason) {
            incompleteResponses.add(replayRequestId);
        }

        @Override
        public void onCapturedClose(
            ConnectionProcessingId connectionProcessingId,
            long capturedOrdinal,
            Instant closeTime
        ) {
            closes.add(connectionProcessingId);
        }

        @Override
        public void onGracefulGenerationCancellation(
            ConnectionProcessingId connectionProcessingId,
            CancellationGrace grace
        ) {
            gracefulCancellations.add(connectionProcessingId);
            graceModes.add(grace);
        }

        @Override
        public void onForceGenerationCancellation(ConnectionProcessingId connectionProcessingId) {
            forceCancellations.add(connectionProcessingId);
        }

        @Override
        public void onConnectionOwnerFinished(ConnectionProcessingId connectionProcessingId) {}
    }

    private static TrafficStream stream(int number, TrafficObservation... observations) {
        return TrafficStream.newBuilder()
            .setNodeId(WRITER)
            .setConnectionId(CONNECTION)
            .setNumber(number)
            .addAllSubStream(List.of(observations))
            .build();
    }

    private static TrafficObservation read(long sequence, String data) {
        return observation(sequence).setRead(ReadObservation.newBuilder().setData(utf8(data))).build();
    }

    private static TrafficObservation write(long sequence, String data) {
        return observation(sequence).setWrite(WriteObservation.newBuilder().setData(utf8(data))).build();
    }

    private static TrafficObservation interim(long sequence, String data) {
        return observation(sequence)
            .setInterimResponse(InterimResponseObservation.newBuilder().setData(utf8(data)))
            .build();
    }

    private static TrafficObservation endOfMessage(long sequence) {
        return observation(sequence)
            .setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance())
            .build();
    }

    private static TrafficObservation close(long sequence) {
        return observation(sequence).setClose(CloseObservation.getDefaultInstance()).build();
    }

    /**
     * Every observation carries its connection-local sequence, because production traffic does:
     * {@code proxyCaptureProtocol §4.1} has the proxy assign a monotonically increasing value per connection,
     * and {@code kafkaLLD §9} validates contiguity against it. A fixture that omitted it would put the
     * validation out of reach.
     */
    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(OBSERVATION_TIME)
            .setConnectionObservationSequence(sequence);
    }

    private static ByteString utf8(String data) {
        return ByteString.copyFromUtf8(data);
    }
}
