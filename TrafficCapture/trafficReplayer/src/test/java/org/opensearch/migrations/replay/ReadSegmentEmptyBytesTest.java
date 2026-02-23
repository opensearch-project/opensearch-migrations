package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadSegmentObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CapturedTrafficToHttpTransactionAccumulator ReadSegment handling uses
 * observation.getReadSegment().getData() rather than observation.getRead().getData().
 *
 * Since the observation has a ReadSegment (not a Read), getRead() returns a default empty
 * ReadObservation, so an empty byte[] is added to packetBytes for every ReadSegment.
 *
 * This test verifies that only the correctly concatenated segment data appears in packetBytes.
 */
@Slf4j
public class ReadSegmentEmptyBytesTest extends InstrumentationTest {

    @Test
    void readSegment_correctlyAccumulatesData() {
        var baseTime = Instant.now();
        var ts = Timestamp.newBuilder()
            .setSeconds(baseTime.getEpochSecond())
            .setNanos(baseTime.getNano())
            .build();

        // Build a TrafficStream with ReadSegments instead of a single Read
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setReadSegment(ReadSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setReadSegment(ReadSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom("Host: localhost\r\n\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setSegmentEnd(EndOfSegmentsIndication.getDefaultInstance()))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(16).setHeadersByteLength(18)))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\n\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setClose(CloseObservation.getDefaultInstance()))
            .build();

        List<RequestResponsePacketPair> results = new ArrayList<>();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request
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

        accumulator.accept(new PojoTrafficStreamAndKey(
            trafficStream,
            PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)
        ));
        accumulator.close();

        Assertions.assertEquals(1, results.size(), "Should have 1 transaction");
        var requestPackets = results.get(0).getRequestData().packetBytes;

        // packetBytes should have exactly 1 entry with the full concatenated segment data
        Assertions.assertEquals(1, requestPackets.size(),
            "packetBytes should have 1 entry with the full concatenated segment data");

        var expectedData = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        Assertions.assertEquals(expectedData, new String(requestPackets.get(0), StandardCharsets.UTF_8),
            "Single entry should have the full concatenated segment data");
    }
}
