package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.trafficcapture.protos.WriteSegmentObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the accumulator correctly handles Write observations arriving
 * while in ACCUMULATING_READS state. This can occur when the capture proxy
 * emits a premature EOM followed by trailing request data that the accumulator
 * treats as a new request, causing the state machine to see a Write (the
 * response) while still in ACCUMULATING_READS.
 *
 * Without proper handling, a Write during READS is silently dropped, leaving
 * the state machine permanently stuck. Subsequent WriteSegment/SegmentEnd
 * observations then trigger a NullPointerException in
 * finalizeRequestSegments() because SegmentEnd is processed against
 * requestData instead of responseData.
 */
@Slf4j
public class WriteObservationDuringReadStateTest extends InstrumentationTest {

    private static Timestamp makeTimestamp(Instant t) {
        return Timestamp.newBuilder()
            .setSeconds(t.getEpochSecond())
            .setNanos(t.getNano())
            .build();
    }

    /**
     * Simulates the failure scenario:
     * 1. A Read observation starts a request (transitions to ACCUMULATING_READS)
     * 2. A Write observation arrives without an intervening EOM
     * 3. More Read/Write pairs follow
     * 4. Eventually a WriteSegment + SegmentEnd arrives
     *
     * Without the fix, the SegmentEnd triggers an NPE because the state
     * machine is stuck in ACCUMULATING_READS and tries to call
     * requestData.finalizeRequestSegments() with null currentSegmentBytes.
     */
    @Test
    void writeObservationDuringReads_doesNotCauseNPE() {
        var baseTime = Instant.now();
        var ts1 = makeTimestamp(baseTime);
        var ts2 = makeTimestamp(baseTime.plusMillis(100));
        var ts3 = makeTimestamp(baseTime.plusMillis(200));
        var ts4 = makeTimestamp(baseTime.plusMillis(300));
        var ts5 = makeTimestamp(baseTime.plusMillis(400));
        var ts6 = makeTimestamp(baseTime.plusMillis(500));
        var ts7 = makeTimestamp(baseTime.plusMillis(600));
        var ts8 = makeTimestamp(baseTime.plusMillis(700));

        // Build a TrafficStream that reproduces the issue:
        // Read (request), Write (response WITHOUT preceding EOM),
        // Read (next request), Write, then WriteSegment + SegmentEnd
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn-write-during-reads")
            .setNodeId("test-node")
            .setNumber(1)
            // Request 1: small read that the proxy treated as complete
            .addSubStream(TrafficObservation.newBuilder().setTs(ts1)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            // EOM for request 1
            .addSubStream(TrafficObservation.newBuilder().setTs(ts1)
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(14).setHeadersByteLength(18)))
            // Trailing data (e.g., chunk terminator) captured as a new Read
            .addSubStream(TrafficObservation.newBuilder().setTs(ts2)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("0\r\n\r\n", StandardCharsets.UTF_8))))
            // Response Write arrives while accumulator is in ACCUMULATING_READS
            .addSubStream(TrafficObservation.newBuilder().setTs(ts3)
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\n\r\n",
                        StandardCharsets.UTF_8))))
            // Next request
            .addSubStream(TrafficObservation.newBuilder().setTs(ts4)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("POST /data HTTP/1.1\r\nHost: localhost\r\n\r\nbody",
                        StandardCharsets.UTF_8))))
            // Response as WriteSegment + SegmentEnd (the trigger for NPE)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts5)
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts6)
                .setWriteSegment(WriteSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom("hello", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts7)
                .setSegmentEnd(EndOfSegmentsIndication.getDefaultInstance()))
            .build();

        List<RequestResponsePacketPair> results = new ArrayList<>();
        AtomicInteger expiredCount = new AtomicInteger(0);
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    return results::add;
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    expiredCount.incrementAndGet();
                }

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            }
        );

        // This must not throw an NPE
        Assertions.assertDoesNotThrow(() -> {
            accumulator.accept(new PojoTrafficStreamAndKey(
                trafficStream,
                PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)
            ));
            accumulator.close();
        }, "Processing a Write observation during ACCUMULATING_READS state must not throw");
    }

    /**
     * Verifies that after a Write-during-READS, subsequent requests on the
     * same connection are still accumulated correctly.
     */
    @Test
    void writeObservationDuringReads_subsequentRequestsStillWork() {
        var baseTime = Instant.now();
        var ts1 = makeTimestamp(baseTime);
        var ts2 = makeTimestamp(baseTime.plusMillis(100));
        var ts3 = makeTimestamp(baseTime.plusMillis(200));
        var ts4 = makeTimestamp(baseTime.plusMillis(300));
        var ts5 = makeTimestamp(baseTime.plusMillis(400));
        var ts6 = makeTimestamp(baseTime.plusMillis(500));

        // Scenario: Read, Write (no EOM between), Read, EOM, Write
        // The second Read+EOM+Write should produce a valid transaction
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn-recovery")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            // Orphaned read (trailing chunk data treated as request)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts1)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("0\r\n\r\n", StandardCharsets.UTF_8))))
            // Write arrives without EOM (response to prior request)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts2)
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\n\r\n",
                        StandardCharsets.UTF_8))))
            // Normal request
            .addSubStream(TrafficObservation.newBuilder().setTs(ts3)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET /healthy HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            // Proper EOM
            .addSubStream(TrafficObservation.newBuilder().setTs(ts4)
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(22).setHeadersByteLength(18)))
            // Response
            .addSubStream(TrafficObservation.newBuilder().setTs(ts5)
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\n\r\nok",
                        StandardCharsets.UTF_8))))
            .build();

        List<RequestResponsePacketPair> results = new ArrayList<>();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    return results::add;
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            }
        );

        Assertions.assertDoesNotThrow(() -> {
            accumulator.accept(new PojoTrafficStreamAndKey(
                trafficStream,
                PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)
            ));
            accumulator.close();
        });

        // Should have at least one completed transaction (the GET /healthy request)
        Assertions.assertFalse(results.isEmpty(),
            "Should have at least one completed transaction after Write-during-READS recovery");
    }
}
