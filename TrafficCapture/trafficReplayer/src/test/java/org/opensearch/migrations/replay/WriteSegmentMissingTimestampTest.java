package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest IReplayContexts ITrafficStreamKey PojoTrafficStreamAndKey PojoTrafficStreamKeyAndContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteSegmentObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

*/
// REBUILD-LIMBO-END(G10)
/**
 * Verifies that WriteSegment handling in CapturedTrafficToHttpTransactionAccumulator calls
 * setLastPacketTimestamp(). Unlike the Write path (which calls addResponseData, setting
 * lastPacketTimestamp), WriteSegment only calls addSegment().
 *
 * If there's no SegmentEnd (e.g., premature close), lastPacketTimestamp remains null,
 * which can cause NPEs in duration calculations.
 *
 * This test verifies that lastPacketTimestamp is set for WriteSegment observations.
 */
// REBUILD-LIMBO-START(G10)
/*
@Slf4j
public class WriteSegmentMissingTimestampTest extends InstrumentationTest {

    @Test
    void writeSegment_setsLastPacketTimestamp() {
        var baseTime = Instant.now();
        var ts = Timestamp.newBuilder()
            .setSeconds(baseTime.getEpochSecond())
            .setNanos(baseTime.getNano())
            .build();

        // TrafficStream: Read → EOM → WriteSegment → WriteSegment → Close (no SegmentEnd)
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("ws-conn")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(16).setHeadersByteLength(18)))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setWriteSegment(WriteSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setWriteSegment(WriteSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom("Content-Length: 0\r\n\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setClose(CloseObservation.getDefaultInstance()))
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

        accumulator.accept(new PojoTrafficStreamAndKey(
            trafficStream,
            PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)
        ));
        accumulator.close();

        Assertions.assertEquals(1, results.size(), "Should have 1 transaction");
        var responseData = results.get(0).getResponseData();
        Assertions.assertNotNull(responseData, "Response data should exist");

        // lastPacketTimestamp is set by WriteSegment handling
        Assertions.assertNotNull(responseData.getLastPacketTimestamp(),
            "lastPacketTimestamp should be set for WriteSegment observations");
    }
}

*/
// REBUILD-LIMBO-END(G10)