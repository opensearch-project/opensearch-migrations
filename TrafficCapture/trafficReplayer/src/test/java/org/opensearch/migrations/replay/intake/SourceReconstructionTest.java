/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.ReplayIntakeMetrics;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.RequestIntentionallyDropped;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.trafficcapture.protos.WriteSegmentObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Covers the source-reconstruction cases in {@code kafkaLLD §17.2}. */
class SourceReconstructionTest {

    private static final String TOPIC = "traffic";
    private static final String WRITER = "writer";
    private static final String CONNECTION = "connection";
    private static final String REQUEST_BYTES = "GET /thing HTTP/1.1\r\nHost: source\r\n\r\n";

    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(false, true);
    private final RootReplayerContext rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
    private final WakeupController wakeupController =
        new WakeupController(() -> {}, rootContext);
    private final KafkaSourceInputQueue sourceInputs = new KafkaSourceInputQueue(wakeupController);
    private final RecordingSink sink = new RecordingSink();
    private final ReplayIntakeOwner owner = new ReplayIntakeOwner(
        new ReplayIntakeInputQueue(),
        sourceInputs,
        sink,
        failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
        rootContext.replayIntakeMetrics,
        ReplayIntakeOwner.RecordObserver.NOOP,
        new PartitionIntakeState.BrokerTimeConfiguration(30_000, 10_000, 5_000)
    );

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
    }

    /**
     * {@code §17.2}: "Periodic {@code TrafficStream} boundaries do not change request or response
     * reconstruction."
     *
     * <p>The proxy detaches a record on a flush interval, so where a boundary falls is a function of time
     * rather than of the traffic. Reconstruction that depended on it would replay differently on a slow day.
     */
    @Test
    void aRequestSplitAcrossRecordsReconstructsTheSameBytesAsOneRecord() {
        var firstHalf = REQUEST_BYTES.substring(0, 12);
        var secondHalf = REQUEST_BYTES.substring(12);
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER, stream(0, read(1, firstHalf)))
            .addTraffic(0, 1, Instant.ofEpochMilli(1_100), WRITER,
                stream(1, read(2, secondHalf), endOfMessage(3)));

        applyAll(script);

        Assertions.assertEquals(1, sink.requests.size(), "one request, however many records carried it");
        Assertions.assertEquals(REQUEST_BYTES, bytesOf(sink.requests.get(0)));
        Assertions.assertEquals(
            List.of(Instant.ofEpochSecond(1)),
            sink.requestFirstByteSourceTimes
        );
        Assertions.assertEquals(
            List.of(Instant.ofEpochSecond(3)),
            sink.requestEndOfMessageSourceTimes
        );
        Assertions.assertEquals(
            List.of(1_100L),
            sink.requestCompletingLogAppendTimes
        );
    }

    /**
     * {@code §17.2}: periodic record boundaries must not change response reconstruction either.
     */
    @Test
    void aResponseSplitAcrossRecordsReconstructsTheSameBytesAsOneRecord() {
        var responseBytes = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbody";
        var firstHalf = responseBytes.substring(0, 20);
        var secondHalf = responseBytes.substring(20);
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER,
                stream(0, read(1, REQUEST_BYTES), endOfMessage(2), write(3, firstHalf)))
            .addTraffic(0, 1, Instant.ofEpochMilli(1_100), WRITER,
                stream(1, write(4, secondHalf), close(5)));

        applyAll(script);

        Assertions.assertEquals(1, sink.responses.size(), "one response, however many records carried it");
        Assertions.assertEquals(responseBytes, bytesOf(sink.responses.get(0)));
    }

    /**
     * {@code §17.2}: "Fresh reconstruction discards an incomplete preceding message using the continuity
     * fields."
     *
     * <p>{@code §9}: the tail "is discarded until the next captured request boundary rather than parsing it as
     * a new request". Parsing it would replay a truncated request the source never answered — and the ordinal
     * still advances past it, so the request that follows is numbered as the source numbered it.
     */
    @Test
    void aFreshLifetimeDiscardsTheTailOfAMessageItDidNotSeeTheStartOf() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            resumedStream(
                3,
                read(1, "tail of a request this lifetime never saw the start of"),
                endOfMessage(2),
                read(3, REQUEST_BYTES),
                endOfMessage(4)
            )
        );

        applyAll(script);

        Assertions.assertEquals(
            1,
            sink.requests.size(),
            () -> "only the request that began inside this lifetime may be replayed, but got "
                + sink.requests.size()
        );
        Assertions.assertEquals(REQUEST_BYTES, bytesOf(sink.requests.get(0)));
        Assertions.assertEquals(
            4L,
            sink.requestIds.get(0).capturedRequestOrdinal(),
            "three prior requests plus the discarded unterminated one: the next the source sent is its fifth"
        );
    }

    /**
     * {@code §17.2}: "The first sequence value establishes the process-local baseline; later values are
     * contiguous."
     *
     * <p>The baseline is whatever the first observation carries — the proxy's counter is per connection and a
     * replayer that joined mid-connection has no way to know where it started. A gap after that means
     * observations were lost or reordered, which {@code §16} makes a capture-protocol violation: a request
     * assembled across a gap would be wrong in a way no later check could detect.
     */
    @Test
    void theFirstSequenceIsTheBaselineAndAGapAfterItIsAProtocolViolation() {
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER,
                stream(0, read(41, "GET /a HTTP/1.1\r\n\r\n"), endOfMessage(42)))
            .addTraffic(0, 1, Instant.ofEpochMilli(1_100), WRITER,
                stream(1, read(44, "GET /b HTTP/1.1\r\n\r\n")));

        applyAll(script);

        Assertions.assertEquals(
            1,
            sink.requests.size(),
            "the first record's sequence of 41 is a baseline, not an error"
        );
        var violations = drainSource().stream()
            .filter(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
            .map(KafkaSourceInput.CaptureProtocolViolationDetected.class::cast)
            .toList();
        Assertions.assertEquals(1, violations.size(), () -> "a sequence gap must be reported: " + violations);
        Assertions.assertTrue(
            violations.get(0).diagnostic().contains("expected 43"),
            () -> "the diagnostic must name the gap: " + violations.get(0).diagnostic()
        );
    }

    /**
     * {@code §17.2}: "Expiration and a later fresh lifetime for the same captured identity coexist without
     * sharing state or messages."
     *
     * <p>{@code §2}: {@code ConnectionProcessingId.localSequence} "distinguishes a later fresh lifetime from an
     * expired lifetime whose target or tuple work is still finishing". Sharing an identity is the one way the
     * two could not coexist — the fresh lifetime's messages would route to the expiring one's owner.
     */
    @Test
    void anExpiredLifetimeAndAFreshOneForTheSameConnectionShareNoIdentity() {
        var first = new RecordScript(TOPIC).addTraffic(
            0, 0, Instant.ofEpochMilli(1_000), WRITER,
            stream(0, read(1, REQUEST_BYTES), endOfMessage(2))
        );
        applyAll(first);
        var expiring = owner.partitionState(first.generation(0)).orElseThrow()
            .lifetimeOf(sink.requestIds.get(0).connectionProcessingId()).orElseThrow();

        var crossing = new RecordScript(TOPIC, first.generation(0).localSequence()).addProbe(
            0, 1, Instant.ofEpochMilli(51_000), "other-writer", "expiration-proof"
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(crossing.generation(0), 1),
            crossing.records()
        ));

        var second = new RecordScript(TOPIC, first.generation(0).localSequence()).addTraffic(
            0, 2, Instant.ofEpochMilli(51_001), WRITER,
            stream(1, read(1, REQUEST_BYTES), endOfMessage(2))
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(second.generation(0), 2),
            second.records()
        ));

        Assertions.assertEquals(2, sink.requestIds.size(), "the fresh lifetime reconstructs its own request");
        var expired = sink.requestIds.get(0).connectionProcessingId();
        var fresh = sink.requestIds.get(1).connectionProcessingId();
        Assertions.assertEquals(
            expired.capturedConnectionId(),
            fresh.capturedConnectionId(),
            "precondition: the same captured connection"
        );
        Assertions.assertNotEquals(
            expired,
            fresh,
            "a fresh lifetime must get its own localSequence, or its messages route to the expired one"
        );
        Assertions.assertEquals(
            SourceConnectionState.Lifetime.EXPIRED,
            expiring.lifetime(),
            "the expired lifetime is not revived by its successor"
        );
        Assertions.assertEquals(
            List.of(expired),
            sink.expiredConnections,
            "only the old process-local owner receives the expiration command"
        );
    }

    @Test
    void retryBoundaryIsEmittedBeforeTheCrossingRecordPayloadCompletesTheFinalResponse() {
        var first = new RecordScript(TOPIC).addTraffic(
            0, 0, Instant.ofEpochMilli(1_000), WRITER,
            stream(0, read(1, REQUEST_BYTES), endOfMessage(2))
        );
        applyAll(first);

        var crossing = new RecordScript(TOPIC, first.generation(0).localSequence()).addTraffic(
            0, 1, Instant.ofEpochMilli(6_000), WRITER,
            stream(1, write(3, "HTTP/1.1 200 OK\r\n\r\n"), connectionException(4))
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(crossing.generation(0), 1),
            crossing.records()
        ));

        Assertions.assertEquals(
            List.of("retry-unavailable", "final-complete"),
            sink.sourceResponseEvents,
            "R - B >= W freezes retry input before the crossing payload can complete the final response"
        );
        Assertions.assertEquals(
            List.of(sink.requestIds.get(0)),
            sink.unavailableRetryResponses
        );
        Assertions.assertTrue(
            sink.completeRetryResponses.isEmpty(),
            "the later final response cannot reverse the boundary"
        );
    }

    @Test
    void backwardSkewBeyondSIsFatalBeforeTheHigherOffsetPayloadIsApplied() {
        var appliedOffsets = new ArrayList<Long>();
        var localSink = new RecordingSink();
        var localOwner = new ReplayIntakeOwner(
            new ReplayIntakeInputQueue(),
            sourceInputs,
            localSink,
            failure -> Assertions.fail("inline owner unexpectedly reported asynchronously: " + failure),
            rootContext.replayIntakeMetrics,
            record -> appliedOffsets.add(record.recordId().offset()),
            new PartitionIntakeState.BrokerTimeConfiguration(100, 10, 50)
        );
        var script = new RecordScript("fatal-skew")
            .addProbe(0, 0, Instant.ofEpochMilli(1_000), "first-writer", "baseline")
            .addTraffic(
                0,
                1,
                Instant.ofEpochMilli(989),
                "late-writer",
                stream(0, read(1, REQUEST_BYTES), endOfMessage(2))
            );
        var generation = script.generation(0);
        localOwner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));

        Assertions.assertThrows(
            PartitionIntakeState.BrokerTimeViolation.class,
            () -> localOwner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                new PartitionBatchRequestId(generation, 0),
                script.records()
            ))
        );
        Assertions.assertEquals(
            List.of(0L),
            appliedOffsets,
            "the invalid higher-offset record must fail before its payload observer"
        );
        Assertions.assertTrue(
            localSink.requests.isEmpty(),
            "the invalid record must not create source assembly or a request"
        );
    }

    /**
     * {@code §17.2}: "{@code ConnectionExceptionObservation} does not close source assembly."
     *
     * <p>{@code §9.3} is explicit that it and {@code DisconnectObservation} "do not perform these steps".
     * Therefore a request whose reads straddle the diagnostic remains one request and reconstructs normally.
     */
    @Test
    void aConnectionExceptionDoesNotResetTheRequestInFlightOrCloseTheConnection() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, "GET /thing HTTP/1.1\r\n"),
                connectionException(2),
                read(3, "Host: source\r\n\r\n"),
                endOfMessage(4)
            )
        );

        applyAll(script);

        Assertions.assertEquals(
            1,
            sink.requests.size(),
            "the diagnostic must not split or discard the request under assembly"
        );
        Assertions.assertEquals(REQUEST_BYTES, bytesOf(sink.requests.get(0)));
        var lifetime = owner.partitionState(script.generation(0)).orElseThrow()
            .lifetimeOf(sink.requestIds.get(0).connectionProcessingId()).orElseThrow();
        Assertions.assertEquals(
            SourceConnectionState.Lifetime.OPEN,
            lifetime.lifetime(),
            "a connection exception is not a close"
        );
    }

    @Test
    void anInformationalWriteBeforeRequestEndDoesNotDiscardOrAdvanceTheRequest() {
        var finalResponse = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, "GET /thing HTTP/1.1\r\n"),
                write(2, "HTTP/1.1 100 Continue\r\n\r\n"),
                read(3, "Host: source\r\n\r\n"),
                endOfMessage(4),
                write(5, finalResponse),
                close(6)
            )
        );

        applyAll(script);

        Assertions.assertEquals(List.of(REQUEST_BYTES), sink.requests.stream().map(SourceReconstructionTest::bytesOf).toList());
        Assertions.assertEquals(0L, sink.requestIds.get(0).capturedRequestOrdinal());
        Assertions.assertEquals(List.of(finalResponse), sink.responses.stream().map(SourceReconstructionTest::bytesOf).toList());
    }

    @Test
    void segmentedInformationalWritesAreIgnoredThroughTheirSegmentEnd() {
        var finalResponse = "HTTP/1.1 204 No Content\r\n\r\n";
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, "GET /thing HTTP/1.1\r\n"),
                writeSegment(2, "HTTP/1.1 100 "),
                writeSegment(3, "Continue\r\n\r\n"),
                segmentEnd(4),
                read(5, "Host: source\r\n\r\n"),
                endOfMessage(6),
                write(7, finalResponse),
                close(8)
            )
        );

        applyAll(script);

        Assertions.assertEquals(List.of(REQUEST_BYTES), sink.requests.stream().map(SourceReconstructionTest::bytesOf).toList());
        Assertions.assertEquals(0L, sink.requestIds.get(0).capturedRequestOrdinal());
        Assertions.assertEquals(List.of(finalResponse), sink.responses.stream().map(SourceReconstructionTest::bytesOf).toList());
    }

    /**
     * {@code §9.2}: a connection exception ends the response as unproven, but does not end the source
     * lifetime. The following request therefore receives the next ordinal rather than colliding with the
     * request whose response just completed.
     */
    @Test
    void aConnectionExceptionCompletesOnlyTheResponseAndTheNextRequestGetsANewIdentity() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, REQUEST_BYTES),
                endOfMessage(2),
                write(3, "HTTP/1.1 200 OK\r\n\r\n"),
                connectionException(4),
                read(5, "GET /next HTTP/1.1\r\n\r\n"),
                endOfMessage(6)
            )
        );

        applyAll(script);

        Assertions.assertEquals(2, sink.requestIds.size(), "the lifetime remains open for the next request");
        Assertions.assertEquals(0L, sink.requestIds.get(0).capturedRequestOrdinal());
        Assertions.assertEquals(1L, sink.requestIds.get(1).capturedRequestOrdinal());
        Assertions.assertEquals(List.of(sink.requestIds.get(0)), sink.completeResponses);
        Assertions.assertEquals(
            List.of(sink.requestIds.get(0)),
            sink.unprovenResponses,
            "a connection exception is a response boundary but not proof that the source finished writing"
        );
        Assertions.assertTrue(sink.incompleteResponses.isEmpty());
    }

    /** A next-request boundary proves the previous response finished and must set {@code keptAlive=true}. */
    @Test
    void aNextRequestBoundaryMarksThePreviousResponseProven() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, REQUEST_BYTES),
                endOfMessage(2),
                write(3, "HTTP/1.1 200 OK\r\n\r\n"),
                read(4, "GET /next HTTP/1.1\r\n\r\n"),
                endOfMessage(5)
            )
        );

        applyAll(script);

        Assertions.assertEquals(List.of(sink.requestIds.get(0)), sink.completeResponses);
        Assertions.assertTrue(
            sink.unprovenResponses.isEmpty(),
            "the following request is the proof that must distinguish kept-alive completion"
        );
    }

    /**
     * {@code procCommit §3.5}: a request receives exactly one final source-response result, and a later
     * connection event cannot replace or repeat an already completed result.
     */
    @Test
    void aLaterCloseDoesNotReplaceOrDuplicateAnAlreadyCompletedResponse() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, REQUEST_BYTES),
                endOfMessage(2),
                write(3, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"),
                read(4, "GET /next HTTP/1.1\r\n"),
                close(5)
            )
        );

        applyAll(script);

        Assertions.assertEquals(
            1,
            sink.requestIds.size(),
            "the closing request prefix never reached its request boundary"
        );
        Assertions.assertEquals(
            sink.requestIds,
            sink.completeResponses,
            "the close must not repeat the response already completed by the next request's first read"
        );
        Assertions.assertTrue(
            sink.unprovenResponses.isEmpty(),
            "the following request read already proved the source finished the completed response"
        );
        Assertions.assertTrue(sink.incompleteResponses.isEmpty());
        sink.assertExactlyOneFinalResponseResultPerRequest();
        assertFinalResponseMetricsBalance();
    }

    /**
     * {@code §9.4}: the proxy may discover capture suppression after recording a request prefix. The marker
     * discards that prefix, advances the source ordinal, and leaves the connection available for later
     * captured requests.
     */
    @Test
    void anIntentionallyDroppedRequestAdvancesTheOrdinalAndLeavesTheConnectionOpen() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, "GET /suppressed HTTP/1.1\r\n"),
                requestIntentionallyDropped(2),
                read(3, REQUEST_BYTES),
                endOfMessage(4)
            )
        );

        applyAll(script);

        Assertions.assertEquals(1, sink.requests.size(), "the suppressed prefix must not become a request");
        Assertions.assertEquals(REQUEST_BYTES, bytesOf(sink.requests.get(0)));
        Assertions.assertEquals(
            1L,
            sink.requestIds.get(0).capturedRequestOrdinal(),
            "the intentionally suppressed request still occupied ordinal zero at the source"
        );
        var lifetime = owner.partitionState(script.generation(0)).orElseThrow()
            .lifetimeOf(sink.requestIds.get(0).connectionProcessingId()).orElseThrow();
        Assertions.assertEquals(SourceConnectionState.Lifetime.OPEN, lifetime.lifetime());
    }

    /**
     * {@code §9/§9.4}: fresh reconstruction reserves the inherited request's ordinal before seeing its tail.
     * A suppression marker ends that discard; it does not describe a second request and must not increment
     * the ordinal again.
     */
    @Test
    void anIntentionallyDroppedInheritedTailEndsDiscardWithoutAdvancingAgain() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            resumedStream(
                3,
                read(1, "tail of the inherited request"),
                requestIntentionallyDropped(2),
                read(3, REQUEST_BYTES),
                endOfMessage(4)
            )
        );

        applyAll(script);

        Assertions.assertTrue(
            drainSource().stream().noneMatch(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance),
            "the marker is a valid boundary for the inherited request"
        );
        Assertions.assertEquals(1, sink.requests.size(), "the inherited tail must not become a replay request");
        Assertions.assertEquals(REQUEST_BYTES, bytesOf(sink.requests.get(0)));
        Assertions.assertEquals(
            4L,
            sink.requestIds.get(0).capturedRequestOrdinal(),
            "three completed requests plus one inherited incomplete request occupy ordinals zero through three"
        );
    }

    /** A drop marker without the captured prefix it describes is a capture-protocol violation. */
    @Test
    void anIntentionallyDroppedMarkerWithoutARequestPrefixIsAProtocolViolation() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(0, requestIntentionallyDropped(1))
        );

        applyAll(script);

        var violations = drainSource().stream()
            .filter(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
            .toList();
        Assertions.assertEquals(1, violations.size());
        Assertions.assertTrue(sink.requests.isEmpty());
    }

    /**
     * {@code §9.3}: {@code CloseObservation} "marks the current process-local source lifetime closed" and
     * "prevents later observations from joining that lifetime".
     */
    @Test
    void anObservationAfterACapturedCloseIsAProtocolViolation() {
        var script = new RecordScript(TOPIC)
            .addTraffic(
                0,
                0,
                Instant.ofEpochMilli(1_000),
                WRITER,
                stream(
                    0,
                    read(1, REQUEST_BYTES),
                    endOfMessage(2),
                    write(3, "HTTP/1.1 200 OK\r\n\r\n"),
                    close(4)
                )
            )
            .addTraffic(
                0,
                1,
                Instant.ofEpochMilli(2_000),
                WRITER,
                stream(
                    1,
                    read(5, "GET /after-the-close HTTP/1.1\r\n\r\n"),
                    endOfMessage(6)
                )
            );

        applyAll(script);

        var violations = drainSource().stream()
            .filter(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
            .toList();
        Assertions.assertEquals(1, violations.size());
        Assertions.assertEquals(1, sink.closes.size(), "the close reaches the connection owner exactly once");
        Assertions.assertEquals(
            1,
            sink.requests.size(),
            "an observation after the close must not become a request on the closed lifetime"
        );
        // §9.2: a captured close is a boundary the replayer observed, so the response completes. What it
        // cannot know is whether the source had finished writing, and keptAlive=false is that admission.
        // Reporting it incomplete would describe the capture rather than the replayer, and would label every
        // response on a closing connection unusable -- which is most of them.
        Assertions.assertEquals(
            List.of(sink.requestIds.get(0)),
            sink.completeResponses,
            "a close ends response assembly, so the response is complete"
        );
        Assertions.assertEquals(
            List.of(sink.requestIds.get(0)),
            sink.unprovenResponses,
            "nothing proved the source finished writing it, so it must be marked unproven"
        );
        Assertions.assertTrue(
            sink.incompleteResponses.isEmpty(),
            "incomplete is reserved for expiration and cancellation, which is the replayer giving up"
        );
        sink.assertExactlyOneFinalResponseResultPerRequest();
    }

    /**
     * The same cutoff must fire before the current {@link SourceConnectionState} can apply another observation,
     * not only when a later Kafka record attempts to allocate a fresh lifetime.
     */
    @Test
    void anObservationLaterInTheClosingTrafficStreamIsAProtocolViolation() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, REQUEST_BYTES),
                endOfMessage(2),
                write(3, "HTTP/1.1 200 OK\r\n\r\n"),
                close(4),
                read(5, "GET /after-the-close HTTP/1.1\r\n\r\n"),
                endOfMessage(6)
            )
        );

        applyAll(script);

        var violations = drainSource().stream()
            .filter(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
            .toList();
        Assertions.assertEquals(1, violations.size());
        Assertions.assertEquals(1, sink.closes.size(), "the close reaches the connection owner exactly once");
        Assertions.assertEquals(
            1,
            sink.requests.size(),
            "the observation following the close must not become another request"
        );
    }

    @Test
    void aBareSegmentEndDuringRequestAssemblyIsIgnored() {
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, REQUEST_BYTES),
                segmentEnd(2),
                endOfMessage(3)
            )
        );

        applyAll(script);

        Assertions.assertEquals(List.of(REQUEST_BYTES), sink.requests.stream()
            .map(SourceReconstructionTest::bytesOf)
            .toList());
        Assertions.assertTrue(
            drainSource().stream().noneMatch(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
        );
    }

    @Test
    void aBareSegmentEndDuringResponseAssemblyIsIgnored() {
        var response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream(
                0,
                read(1, REQUEST_BYTES),
                endOfMessage(2),
                segmentEnd(3),
                write(4, response),
                close(5)
            )
        );

        applyAll(script);

        Assertions.assertEquals(List.of(response), sink.responses.stream()
            .map(SourceReconstructionTest::bytesOf)
            .toList());
        Assertions.assertTrue(
            drainSource().stream().noneMatch(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance)
        );
    }

    /**
     * Expiration is the case that genuinely is incomplete: replay intake stopped assembling rather than
     * observing an end.
     *
     * <p>This is the contrast that gives {@code §9.2}'s two outcomes their meaning. Both this and the captured
     * close end a response that was mid-assembly, and only one of them is something the replayer did.
     */
    @Test
    void anExpiredLifetimeReportsItsResponseIncomplete() {
        var script = new RecordScript(TOPIC).addTraffic(
            0, 0, Instant.ofEpochMilli(1_000), WRITER,
            stream(0, read(1, REQUEST_BYTES), endOfMessage(2), write(3, "HTTP/1.1 200 OK\r\n"))
        );
        applyAll(script);
        var crossing = new RecordScript(TOPIC, script.generation(0).localSequence()).addProbe(
            0, 1, Instant.ofEpochMilli(51_000), "other-writer", "expiration-proof"
        );
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(crossing.generation(0), 1),
            crossing.records()
        ));

        Assertions.assertEquals(
            List.of(sink.requestIds.get(0)),
            sink.incompleteResponses,
            "expiration is the replayer stopping, which is what incomplete states"
        );
        Assertions.assertTrue(
            sink.completeResponses.isEmpty(),
            "an expired response must not be reported complete, proven or otherwise"
        );
        sink.assertExactlyOneFinalResponseResultPerRequest();
    }

    // ---------------------------------------------------------------- driving

    private void applyAll(RecordScript script) {
        var generation = script.generation(0);
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
        owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
            new PartitionBatchRequestId(generation, 0),
            script.records()
        ));
    }

    private List<KafkaSourceInput> drainSource() {
        return sourceInputs.drain();
    }

    private void assertFinalResponseMetricsBalance() {
        var metrics = telemetry.getFinishedMetrics();
        var requests = InMemoryInstrumentationBundle.getMetricValueOrZero(
            metrics,
            ReplayIntakeMetrics.MetricNames.REQUESTS_RECONSTITUTED
        );
        var finalResponses =
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                metrics,
                ReplayIntakeMetrics.MetricNames.RESPONSES_PROVEN_COMPLETE
            )
                + InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.RESPONSES_UNPROVEN_COMPLETE
                )
                + InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.RESPONSES_INCOMPLETE
                );
        Assertions.assertEquals(
            requests,
            finalResponses,
            "terminal source-response metrics must be a projection of the same one-result-per-request lifecycle"
        );
    }

    private static String bytesOf(HttpMessageAndTimestamp message) {
        var joined = new StringBuilder();
        message.stream().forEach(packet -> joined.append(new String(packet, StandardCharsets.UTF_8)));
        return joined.toString();
    }

    // ---------------------------------------------------------------- fixtures

    private static final class RecordingSink implements SourceAssemblySink {
        private final List<HttpMessageAndTimestamp.Request> requests = new ArrayList<>();
        private final List<ReplayRequestId> requestIds = new ArrayList<>();
        private final List<Instant> requestFirstByteSourceTimes = new ArrayList<>();
        private final List<Instant> requestEndOfMessageSourceTimes = new ArrayList<>();
        private final List<Long> requestCompletingLogAppendTimes = new ArrayList<>();
        private final List<ReplayRequestId> completeResponses = new ArrayList<>();
        private final List<HttpMessageAndTimestamp.Response> responses = new ArrayList<>();
        /** Completed, but with nothing proving the source finished writing — {@code §9.2}'s {@code keptAlive}. */
        private final List<ReplayRequestId> unprovenResponses = new ArrayList<>();
        private final List<ReplayRequestId> incompleteResponses = new ArrayList<>();
        private final List<ConnectionProcessingId> closes = new ArrayList<>();
        private final List<ReplayRequestId> completeRetryResponses = new ArrayList<>();
        private final List<ReplayRequestId> unavailableRetryResponses = new ArrayList<>();
        private final List<ConnectionProcessingId> expiredConnections = new ArrayList<>();
        private final List<String> sourceResponseEvents = new ArrayList<>();

        @Override
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant requestFirstByteSourceTime,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime
        ) {
            requests.add(request);
            requestIds.add(replayRequestId);
            requestFirstByteSourceTimes.add(requestFirstByteSourceTime);
            requestEndOfMessageSourceTimes.add(requestEndOfMessageSourceTime);
            requestCompletingLogAppendTimes.add(requestCompletingLogAppendTime);
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            sourceResponseEvents.add("final-complete");
            completeResponses.add(replayRequestId);
            responses.add(response);
            if (!keptAlive) {
                unprovenResponses.add(replayRequestId);
            }
        }

        @Override
        public void onRetrySourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response
        ) {
            sourceResponseEvents.add("retry-complete");
            completeRetryResponses.add(replayRequestId);
        }

        @Override
        public void onSourceResponseUnavailableForRetry(ReplayRequestId replayRequestId) {
            sourceResponseEvents.add("retry-unavailable");
            unavailableRetryResponses.add(replayRequestId);
        }

        @Override
        public void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason) {
            sourceResponseEvents.add("final-incomplete");
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
        public void onConnectionOwnerFinished(ConnectionProcessingId connectionProcessingId) {}

        @Override
        public void onCapturedConnectionExpired(ConnectionProcessingId connectionProcessingId) {
            expiredConnections.add(connectionProcessingId);
        }

        private void assertExactlyOneFinalResponseResultPerRequest() {
            var finalResults = new ArrayList<ReplayRequestId>(
                completeResponses.size() + incompleteResponses.size()
            );
            finalResults.addAll(completeResponses);
            finalResults.addAll(incompleteResponses);
            var distinctRequests = new java.util.HashSet<>(requestIds);
            Assertions.assertEquals(
                requestIds.size(),
                distinctRequests.size(),
                "each reconstituted request must have a distinct identity"
            );
            Assertions.assertEquals(
                requestIds.size(),
                finalResults.size(),
                "every reconstituted request must receive one final source-response result"
            );
            Assertions.assertEquals(
                distinctRequests,
                new java.util.HashSet<>(finalResults),
                "final source-response results must neither omit nor invent a request"
            );
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

    /**
     * A record whose continuity fields say the connection was already active: {@code priorRequestsReceived}
     * requests have completed and the last observation was an unterminated read.
     */
    private static TrafficStream resumedStream(int priorRequestsReceived, TrafficObservation... observations) {
        return TrafficStream.newBuilder()
            .setNodeId(WRITER)
            .setConnectionId(CONNECTION)
            .setNumber(0)
            .setPriorRequestsReceived(priorRequestsReceived)
            .setLastObservationWasUnterminatedRead(true)
            .addAllSubStream(List.of(observations))
            .build();
    }

    private static TrafficObservation read(long sequence, String data) {
        return observation(sequence)
            .setRead(ReadObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation write(long sequence, String data) {
        return observation(sequence)
            .setWrite(WriteObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation writeSegment(long sequence, String data) {
        return observation(sequence)
            .setWriteSegment(WriteSegmentObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation segmentEnd(long sequence) {
        return observation(sequence)
            .setSegmentEnd(EndOfSegmentsIndication.getDefaultInstance())
            .build();
    }

    private static TrafficObservation endOfMessage(long sequence) {
        return observation(sequence)
            .setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance())
            .build();
    }

    private static TrafficObservation connectionException(long sequence) {
        return observation(sequence)
            .setConnectionException(ConnectionExceptionObservation.newBuilder().setMessage("reset"))
            .build();
    }

    private static TrafficObservation requestIntentionallyDropped(long sequence) {
        return observation(sequence)
            .setRequestDropped(RequestIntentionallyDropped.getDefaultInstance())
            .build();
    }

    private static TrafficObservation close(long sequence) {
        return observation(sequence)
            .setClose(org.opensearch.migrations.trafficcapture.protos.CloseObservation.getDefaultInstance())
            .build();
    }

    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(sequence).build())
            .setConnectionObservationSequence(sequence);
    }
}
