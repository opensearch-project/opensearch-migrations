package org.opensearch.migrations.replay;

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
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.InterimResponseObservation;
import org.opensearch.migrations.trafficcapture.protos.InterimResponseSegmentObservation;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class InterimResponseAccumulatorTest extends InstrumentationTest {

    private static final String HEADERS = "POST /x HTTP/1.1\r\nHost: l\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n";
    private static final String BODY = "hello";
    private static final String INTERIM = "HTTP/1.1 100 Continue\r\n\r\n";
    private static final String FINAL_RESP = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
    private static final String SIMPLE_GET = "GET / HTTP/1.1\r\nHost: l\r\n\r\n";
    private static final String SIMPLE_RESP = "HTTP/1.1 200 OK\r\n\r\nbody";

    private static Timestamp ts(int millis) {
        var t = Instant.EPOCH.plusMillis(millis);
        return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
    }

    private static TrafficObservation read(int ms, String s) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setRead(ReadObservation.newBuilder().setData(ByteString.copyFrom(s, StandardCharsets.UTF_8))).build();
    }

    private static TrafficObservation write(int ms, String s) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setWrite(WriteObservation.newBuilder().setData(ByteString.copyFrom(s, StandardCharsets.UTF_8))).build();
    }

    private static TrafficObservation interim(int ms, String s) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setInterimResponse(InterimResponseObservation.newBuilder()
                .setData(ByteString.copyFrom(s, StandardCharsets.UTF_8))).build();
    }

    private static TrafficObservation interimSeg(int ms, String s) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setInterimResponseSegment(InterimResponseSegmentObservation.newBuilder()
                .setData(ByteString.copyFrom(s, StandardCharsets.UTF_8))).build();
    }

    private static TrafficObservation eom(int ms, int firstLine, int headers) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                .setFirstLineByteLength(firstLine).setHeadersByteLength(headers)).build();
    }

    private static TrafficObservation segmentEnd(int ms) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setSegmentEnd(EndOfSegmentsIndication.getDefaultInstance()).build();
    }

    private static TrafficObservation close(int ms) {
        return TrafficObservation.newBuilder().setTs(ts(ms))
            .setClose(CloseObservation.getDefaultInstance()).build();
    }

    private static TrafficStream stream(String connId, TrafficObservation... observations) {
        var builder = TrafficStream.newBuilder().setConnectionId(connId).setNodeId("n").setNumberOfThisLastChunk(0);
        for (var o : observations) builder.addSubStream(o);
        return builder.build();
    }

    private static String packetsAsString(java.util.List<byte[]> packets) {
        var bos = new java.io.ByteArrayOutputStream();
        packets.forEach(p -> bos.writeBytes(p));
        return bos.toString(StandardCharsets.UTF_8);
    }

    @Test
    void interimResponseDuringReadsIsCapturedAndDoesNotDisruptState() {
        var pairs = runAccumulator(stream("c1",
            read(0, HEADERS), interim(10, INTERIM), read(20, BODY),
            eom(20, 19, HEADERS.length()), write(30, FINAL_RESP), close(40)));
        Assertions.assertEquals(1, pairs.size());
        var pair = pairs.get(0);
        var requestStr = packetsAsString(pair.getRequestData().packetBytes);
        Assertions.assertTrue(requestStr.contains("POST /x") && requestStr.endsWith(BODY));
        Assertions.assertEquals(FINAL_RESP, packetsAsString(pair.getResponseData().packetBytes));
        Assertions.assertEquals(1, pair.getInterimResponseData().size());
        Assertions.assertEquals(INTERIM,
            new String(pair.getInterimResponseData().get(0), StandardCharsets.UTF_8));
    }

    @Test
    void interimResponseSegmentsAreFinalizedOnSegmentEnd() {
        var pairs = runAccumulator(stream("c2",
            read(0, HEADERS),
            interimSeg(5, "HTTP/1.1 100 "), interimSeg(7, "Continue\r\n\r\n"), segmentEnd(10),
            read(20, BODY), eom(20, 19, HEADERS.length()),
            write(30, FINAL_RESP), close(40)));
        var pair = pairs.get(0);
        Assertions.assertEquals(1, pair.getInterimResponseData().size());
        Assertions.assertEquals(INTERIM,
            new String(pair.getInterimResponseData().get(0), StandardCharsets.UTF_8));
        Assertions.assertEquals(FINAL_RESP, packetsAsString(pair.getResponseData().packetBytes));
    }

    @Test
    void interimResponseDuringWritesThrowsMalformedException() {
        var ts = stream("c3",
            read(0, SIMPLE_GET), eom(10, 14, 18), write(20, SIMPLE_RESP),
            interim(25, INTERIM));
        var ex = Assertions.assertThrows(MalformedTrafficStreamException.class, () -> runAccumulator(ts));
        Assertions.assertTrue(ex.getMessage().contains("ACCUMULATING_WRITES"));
    }

    @Test
    void interimResponseInWaitingStateThrowsMalformedException() {
        var ts = stream("c4", interim(0, INTERIM));
        var ex = Assertions.assertThrows(MalformedTrafficStreamException.class, () -> runAccumulator(ts));
        Assertions.assertTrue(ex.getMessage().contains("WAITING_FOR_NEXT_READ_CHUNK"));
    }

    private List<RequestResponsePacketPair> runAccumulator(TrafficStream trafficStream) {
        List<RequestResponsePacketPair> results = new ArrayList<>();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request, boolean isResumedConnection) {
                    return results::add;
                }
                @Override public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> keys) {}
                @Override public void onConnectionClose(int n,
                    @NonNull IReplayContexts.IChannelKeyContext ctx, int s,
                    RequestResponsePacketPair.ReconstructionStatus status, @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> keys) {}
                @Override public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            });
        accumulator.accept(new PojoTrafficStreamAndKey(trafficStream,
            PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)));
        accumulator.close();
        return results;
    }
}
