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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the replayer's handling of InterimResponse observations
 * in a captured traffic stream.
 *
 * Builds a TrafficStream that mirrors what the capture proxy emits for a
 * 100-Continue exchange:
 *   Read(headers with Expect: 100-continue)
 *   InterimResponse(HTTP/1.1 100 Continue)
 *   Read(body)
 *   EOM
 *   Write(HTTP/1.1 200 OK ...)
 *
 * Verifies that the accumulator:
 *   1. Does not throw when InterimResponse arrives during ACCUMULATING_READS.
 *   2. Preserves the interim bytes on the reconstructed RequestResponsePacketPair.
 *   3. Correctly reconstructs the request (headers + body) and response.
 */
@Slf4j
public class InterimResponseAccumulatorTest extends InstrumentationTest {

    private static Timestamp ts(Instant t) {
        return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
    }

    private static final String REQUEST_HEADERS =
        "POST /test HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Content-Length: 5\r\n"
        + "Expect: 100-continue\r\n"
        + "\r\n";
    private static final String REQUEST_BODY = "hello";
    private static final String INTERIM_100 = "HTTP/1.1 100 Continue\r\n\r\n";
    private static final String FINAL_RESPONSE =
        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";

    @Test
    void interimResponseDuringReadsIsCapturedAndDoesNotDisruptState() {
        var t = Instant.now();
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn-100")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom(REQUEST_HEADERS, StandardCharsets.UTF_8))))
            // Interim 100 Continue arrives during ACCUMULATING_READS
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(10)))
                .setInterimResponse(InterimResponseObservation.newBuilder()
                    .setData(ByteString.copyFrom(INTERIM_100, StandardCharsets.UTF_8))))
            // Body arrives next
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(20)))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom(REQUEST_BODY, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(20)))
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(19).setHeadersByteLength(REQUEST_HEADERS.length())))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(30)))
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom(FINAL_RESPONSE, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(40)))
                .setClose(CloseObservation.getDefaultInstance()))
            .build();

        var pairs = runAccumulator(trafficStream);

        Assertions.assertEquals(1, pairs.size(),
            "Expected exactly one reconstructed transaction");
        var pair = pairs.get(0);

        // Request bytes must contain headers + body.
        var requestBytes = pair.getRequestData().packetBytes.stream()
            .reduce(new byte[0], InterimResponseAccumulatorTest::concat);
        var requestStr = new String(requestBytes, StandardCharsets.UTF_8);
        Assertions.assertTrue(requestStr.contains("POST /test HTTP/1.1"),
            "Reconstructed request should contain the POST line, got: " + requestStr);
        Assertions.assertTrue(requestStr.endsWith(REQUEST_BODY),
            "Reconstructed request should end with the body bytes, got: " + requestStr);

        // Response bytes must be the FINAL response only (no 100 Continue mixed in).
        var responseBytes = pair.getResponseData().packetBytes.stream()
            .reduce(new byte[0], InterimResponseAccumulatorTest::concat);
        var responseStr = new String(responseBytes, StandardCharsets.UTF_8);
        Assertions.assertEquals(FINAL_RESPONSE, responseStr,
            "Reconstructed response should be the final 200 OK only");

        // InterimResponse bytes must be preserved on the pair.
        Assertions.assertNotNull(pair.getInterimResponseData(),
            "InterimResponseData should be populated on the pair");
        Assertions.assertEquals(1, pair.getInterimResponseData().size(),
            "Expected one interim response packet");
        Assertions.assertEquals(INTERIM_100,
            new String(pair.getInterimResponseData().get(0), StandardCharsets.UTF_8),
            "InterimResponseData should contain the 100 Continue bytes");
    }

    /**
     * An InterimResponse observed during ACCUMULATING_WRITES is malformed — 1xx
     * interim responses can only arrive during the request phase. The accumulator
     * must throw a MalformedTrafficStreamException to surface the corruption rather
     * than silently producing an incorrect replay.
     */
    @Test
    void interimResponseDuringWritesThrowsMalformedException() {
        var t = Instant.now();
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn-malformed")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(10)))
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(14).setHeadersByteLength(18)))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(20)))
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\n\r\nbody",
                        StandardCharsets.UTF_8))))
            // Malformed: an InterimResponse in the middle of writes
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(25)))
                .setInterimResponse(InterimResponseObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 100 Continue\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .build();

        var ex = Assertions.assertThrows(MalformedTrafficStreamException.class,
            () -> runAccumulator(trafficStream),
            "InterimResponse during ACCUMULATING_WRITES must throw MalformedTrafficStreamException");
        Assertions.assertTrue(ex.getMessage().contains("ACCUMULATING_WRITES"),
            "Exception message should identify the offending state. Got: " + ex.getMessage());
    }

    /**
     * An InterimResponse arriving as the FIRST observation in a fresh stream
     * (initial state WAITING_FOR_NEXT_READ_CHUNK, before any request) is malformed —
     * the server has no request in flight to send 1xx for. The accumulator must
     * throw rather than silently accept stray data.
     */
    @Test
    void interimResponseInWaitingStateThrowsMalformedException() {
        var t = Instant.now();
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn-waiting-interim")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            // No prior request — accumulation starts in WAITING_FOR_NEXT_READ_CHUNK.
            // Stray InterimResponse before any Read is malformed.
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t))
                .setInterimResponse(InterimResponseObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 100 Continue\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .build();

        var ex = Assertions.assertThrows(MalformedTrafficStreamException.class,
            () -> runAccumulator(trafficStream),
            "InterimResponse in WAITING_FOR_NEXT_READ_CHUNK must throw MalformedTrafficStreamException");
        Assertions.assertTrue(ex.getMessage().contains("WAITING_FOR_NEXT_READ_CHUNK"),
            "Exception message should identify the offending state. Got: " + ex.getMessage());
    }

    @Test
    void interimResponseSegmentsAreFinalizedOnSegmentEnd() {
        var t = Instant.now();
        // Split the 100 Continue across two segment observations.
        var interimPart1 = "HTTP/1.1 100 ";
        var interimPart2 = "Continue\r\n\r\n";
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("test-conn-100-seg")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom(REQUEST_HEADERS, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(5)))
                .setInterimResponseSegment(InterimResponseSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom(interimPart1, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(7)))
                .setInterimResponseSegment(InterimResponseSegmentObservation.newBuilder()
                    .setData(ByteString.copyFrom(interimPart2, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(10)))
                .setSegmentEnd(EndOfSegmentsIndication.getDefaultInstance()))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(20)))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom(REQUEST_BODY, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(20)))
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(19).setHeadersByteLength(REQUEST_HEADERS.length())))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(30)))
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom(FINAL_RESPONSE, StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(t.plusMillis(40)))
                .setClose(CloseObservation.getDefaultInstance()))
            .build();

        var pairs = runAccumulator(trafficStream);
        Assertions.assertEquals(1, pairs.size());
        var pair = pairs.get(0);

        // The two segments must be concatenated into one interim response packet.
        Assertions.assertNotNull(pair.getInterimResponseData());
        Assertions.assertEquals(1, pair.getInterimResponseData().size());
        Assertions.assertEquals(INTERIM_100,
            new String(pair.getInterimResponseData().get(0), StandardCharsets.UTF_8));

        // Request and response must still be intact.
        var requestStr = new String(
            pair.getRequestData().packetBytes.stream().reduce(new byte[0], InterimResponseAccumulatorTest::concat),
            StandardCharsets.UTF_8);
        Assertions.assertTrue(requestStr.endsWith(REQUEST_BODY));

        var responseStr = new String(
            pair.getResponseData().packetBytes.stream().reduce(new byte[0], InterimResponseAccumulatorTest::concat),
            StandardCharsets.UTF_8);
        Assertions.assertEquals(FINAL_RESPONSE, responseStr);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private List<RequestResponsePacketPair> runAccumulator(TrafficStream trafficStream) {
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
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {}

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {}

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            }
        );
        accumulator.accept(new PojoTrafficStreamAndKey(
            trafficStream,
            PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)
        ));
        accumulator.close();
        return results;
    }
}
