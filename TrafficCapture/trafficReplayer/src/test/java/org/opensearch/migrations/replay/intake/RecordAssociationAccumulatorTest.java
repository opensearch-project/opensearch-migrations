/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.KafkaSourceRootContext;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
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
 * Record accounting across source assembly, against {@link RecordScript} as an independent oracle.
 *
 * <p>The oracle matters more than the assertions: the script states which associations each record is expected
 * to hold, written out literally by the test rather than derived from the same code under test. A test that
 * computed its expectation from assembly transitions would agree with any consistent bug.
 *
 * <p>Covers {@code kafkaLLD §8.3}'s mixed record and {@code §9.2}'s "the request's record associations remain
 * until {@code RequestProcessingFinished}", which are the two cases Plan A's G3 exit line names.
 */
class RecordAssociationAccumulatorTest {

    private static final String TOPIC = "traffic";
    private static final String WRITER = "writer";
    private static final String CONNECTION = "connection";
    private static final Timestamp OBSERVATION_TIME = Timestamp.newBuilder().setSeconds(1).build();

    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(false, false);
    // REBUILD-LIMBO-NOTE(G3): becomes RootReplayerContext.
    private final WakeupController wakeupController =
        new WakeupController(() -> {}, new KafkaSourceRootContext(telemetry.openTelemetrySdk));
    private final KafkaSourceInputQueue sourceInputs = new KafkaSourceInputQueue(wakeupController);
    private final ReplayIntakeInputQueue intakeInputs = new ReplayIntakeInputQueue();
    private final RecordingSink sink = new RecordingSink();
    private final ReplayIntakeOwner owner = new ReplayIntakeOwner(
        intakeInputs,
        sourceInputs,
        sink,
        failure -> Assertions.fail("replay intake failed: " + failure.getMessage())
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
    void mixedKeepAliveRecordMatchesLiteralRecordScriptAssociations() {
        var stream = stream(
            0,
            read(1, "GET /0 HTTP/1.1\r\n\r\n"),
            endOfMessage(2),
            write(3, "HTTP/1.1 200 OK\r\n\r\n"),
            read(4, "GET /1 HTTP/1.1\r\n\r\n")
        );
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream,
            "request-0",
            "assembly-1"
        );
        assignAndApply(script);

        var recordId = new KafkaRecordId(script.generation(0), 0);
        script.assertAssociations(recordId, associationNames(associationsOf(script, recordId)));
        Assertions.assertFalse(completionEmitted(script, recordId));

        finishRequest(script, 0);
        Assertions.assertFalse(
            completionEmitted(script, recordId),
            "request N+1 assembly must continue to hold the mixed record"
        );

        // Request 1 never reaches end-of-message, so it expires without reconstitution: §8.2 releases its
        // assembly associations and no tuple is owed.
        intakeState(script).associationFinished(assembly(script, 1));

        Assertions.assertEquals(List.of(recordId), sourceCompletions());
        script.assertExhausted();
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
        var response = stream(1, write(3, "HTTP/1.1 200 OK\r\n\r\n"));
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER, first, "request-0")
            .addTraffic(0, 1, Instant.ofEpochMilli(2_000), WRITER, response, "request-0");
        assignAndApply(script);

        for (var scriptedRecord : script.records()) {
            script.assertAssociations(
                scriptedRecord.recordId(),
                associationNames(associationsOf(script, scriptedRecord.recordId()))
            );
        }

        Assertions.assertTrue(sourceCompletions().isEmpty());
        finishRequest(script, 0);

        Assertions.assertEquals(
            List.of(
                new KafkaRecordId(script.generation(0), 0),
                new KafkaRecordId(script.generation(0), 1)
            ),
            sourceCompletions(),
            "both records finish together, and only once the request's processing is complete"
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
    }

    /**
     * {@code §7.1}: {@code PAYLOAD_NOT_SET} is a protocol violation, and {@code §16} sends it to the Kafka
     * source rather than finishing the record.
     */
    @Test
    void anEnvelopeWithNoPayloadIsAProtocolViolationRatherThanACompletedRecord() {
        var script = new RecordScript(TOPIC).addPayloadNotSet(0, 0, Instant.ofEpochMilli(1_000), WRITER);
        assignAndApply(script);

        var violations = drainSourceInputs().stream()
            .filter(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
            .toList();
        Assertions.assertEquals(1, violations.size(), "the violation must reach the source");
    }

    // ---------------------------------------------------------------- driving

    private void assignAndApply(RecordScript script) {
        var generation = script.generation(0);
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 1),
            script.records()
        ));
        while (script.hasNext()) {
            script.next();
        }
    }

    private PartitionIntakeState intakeState(RecordScript script) {
        return owner.partitionState(script.generation(0)).orElseThrow();
    }

    private Set<RecordAssociationId> associationsOf(RecordScript script, KafkaRecordId recordId) {
        return intakeState(script).associations(recordId);
    }

    private boolean completionEmitted(RecordScript script, KafkaRecordId recordId) {
        return intakeState(script).recordCompletionEmitted(recordId);
    }

    private void finishRequest(RecordScript script, long capturedRequestOrdinal) {
        owner.applyOnCallingThread(new ReplayIntakeInput.RequestProcessingFinished(
            script.generation(0),
            new ReplayRequestId(lifetimeOf(script), capturedRequestOrdinal)
        ));
    }

    private RecordAssociationId assembly(RecordScript script, long capturedRequestOrdinal) {
        return new RecordAssociationId.RequestAssembly(lifetimeOf(script), capturedRequestOrdinal);
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

    // ---------------------------------------------------------------- oracle vocabulary

    /**
     * Renders associations the way {@link RecordScript} states them.
     *
     * <p>Deliberately lossy and deliberately literal: the script's expectations are written as strings so that
     * neither side can be derived from the other.
     */
    private static Collection<String> associationNames(Set<RecordAssociationId> associations) {
        return associations.stream().map(association -> switch (association) {
            case RecordAssociationId.Request request ->
                "request-" + request.replayRequestId().capturedRequestOrdinal();
            case RecordAssociationId.RequestAssembly assembly ->
                "assembly-" + assembly.capturedRequestOrdinal();
            case RecordAssociationId.TerminalConnection terminal ->
                "terminal-" + terminal.connectionProcessingId();
        }).toList();
    }

    // ---------------------------------------------------------------- fixtures

    /** Records what source assembly emitted, so a test can read the lifetime it allocated. */
    private static final class RecordingSink implements SourceAssemblySink {
        private final List<ReplayRequestId> reconstituted = new ArrayList<>();
        private final List<ReplayRequestId> completeResponses = new ArrayList<>();
        private final List<ReplayRequestId> incompleteResponses = new ArrayList<>();
        private final List<ConnectionProcessingId> closes = new ArrayList<>();

        @Override
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant sourceEventTime,
            long requestCompletingLogAppendTime
        ) {
            reconstituted.add(replayRequestId);
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response
        ) {
            completeResponses.add(replayRequestId);
        }

        @Override
        public void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason) {
            incompleteResponses.add(replayRequestId);
        }

        @Override
        public void onCapturedClose(ConnectionProcessingId connectionProcessingId, Instant closeTime) {
            closes.add(connectionProcessingId);
        }
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

    private static TrafficObservation endOfMessage(long sequence) {
        return observation(sequence)
            .setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance())
            .build();
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
