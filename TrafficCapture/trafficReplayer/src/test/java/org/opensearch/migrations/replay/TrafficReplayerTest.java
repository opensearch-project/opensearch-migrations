package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import javax.net.ssl.SSLException;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
class TrafficReplayerTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";
    private static String TEST_TRAFFIC_STREAM_ID_STRING = "testId";
    private static String FAKE_READ_PACKET_DATA = "Useless packet data for test";
    private static String FAKE_EXCEPTION_DATA = "Mock Exception Message for testing";

    private static TrafficStream makeTrafficStream(Instant t, int trafficChunkNumber) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        var tsb = TrafficStream.newBuilder()
                .setNumber(trafficChunkNumber);
        // TODO - add something for setNumberOfThisLastChunk.  There's no point in doing that now though
        //        because the code doesn't make any distinction between the very last one and the previous ones
        return tsb.setNodeId(TEST_NODE_ID_STRING)
                .setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setRead(ReadObservation.newBuilder()
                                .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setConnectionException(ConnectionExceptionObservation.newBuilder().build().newBuilder()
                                .setMessage(FAKE_EXCEPTION_DATA)
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setRead(ReadObservation.newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setConnectionException(ConnectionExceptionObservation.newBuilder().build().newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setRead(ReadObservation.newBuilder()
                                .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setRead(ReadObservation.newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                                .setFirstLineByteLength(17)
                                .setHeadersByteLength(72)
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setWrite(WriteObservation.newBuilder()
                                .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setWrite(WriteObservation.newBuilder()
                                .build())
                        .build())
                // Don't need to add more because this gets looped multiple times (with the same connectionId)
                .build();
    }

    @Test
    public void testDelimitedDeserializer() throws IOException {
        final Instant timestamp = Instant.now();
        byte[] serializedChunks = synthesizeTrafficStreamsIntoByteArray(timestamp, 3);
        try (var bais = new ByteArrayInputStream(serializedChunks)) {
            AtomicInteger counter = new AtomicInteger(0);
            Assertions.assertTrue(new InputStreamOfTraffic(bais).supplyTrafficFromSource()
                    .allMatch(ts-> {
                        var i = counter.incrementAndGet();
                        var expectedStream = makeTrafficStream(timestamp.plus(i-1, ChronoUnit.SECONDS), i);
                        var isEqual = ts.equals(expectedStream);
                        if (!isEqual) {
                            log.error("Expected trafficStream: "+expectedStream);
                            log.error("Observed trafficStream: "+ts);
                        }
                        return isEqual;
                    }));
        }
    }

    private byte[] synthesizeTrafficStreamsIntoByteArray(Instant timestamp, int numStreams) throws IOException {
        byte[] serializedChunks;
        try (var baos = new ByteArrayOutputStream()) {
            for (int i=0; i<numStreams; ++i) {
                makeTrafficStream(timestamp.plus(i, ChronoUnit.SECONDS), i+1).writeDelimitedTo(baos);
            }
            serializedChunks = baos.toByteArray();
        }
        return serializedChunks;
    }

    @Test
    public void testReader() throws IOException, URISyntaxException {
        var tr = new TrafficReplayer(new URI("http://localhost:9200"), null,false);
        List<List<byte[]>> byteArrays = new ArrayList<>();
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30),
                        (id,request) -> {
                            var bytesList = request.stream().collect(Collectors.toList());
                            byteArrays.add(bytesList);
                            Assertions.assertEquals(FAKE_READ_PACKET_DATA, collectBytesToUtf8String(bytesList));
                        },
                        fullPair -> {
                            var responseBytes = fullPair.responseData.packetBytes.stream().collect(Collectors.toList());
                            Assertions.assertEquals(FAKE_READ_PACKET_DATA, collectBytesToUtf8String(responseBytes));
                        }
                );
        var bytes = synthesizeTrafficStreamsIntoByteArray(Instant.now(), 3);

        try (var bais = new ByteArrayInputStream(bytes)) {
            try (var trafficSource = new InputStreamOfTraffic(bais)) {
                tr.runReplay(trafficSource.supplyTrafficFromSource(), trafficAccumulator);
            }
        }
        Assertions.assertEquals(3, byteArrays.size());
        Assertions.assertTrue(byteArrays.stream().allMatch(ba->ba.size()==2));
    }

    @Test
    public void testMissingStreamCausesWarning() throws URISyntaxException, IOException, ExecutionException, InterruptedException {
        var tr = new TrafficReplayer(new URI("http://localhost:9200"), null,false);
        var gotWarning = new AtomicBoolean();
        var gotAnythingElse = new AtomicBoolean();
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30),
                        (id,request) -> { gotAnythingElse.set(true); },
                        fullPair -> { gotAnythingElse.set(true); },
                        accum -> gotWarning.set(true));
        byte[] serializedChunks;
        try (var baos = new ByteArrayOutputStream()) {
            makeTrafficStream(Instant.now(), 3).writeDelimitedTo(baos);
            serializedChunks = baos.toByteArray();
        }
        try (var bais = new ByteArrayInputStream(serializedChunks)) {
            try (var trafficSource = new InputStreamOfTraffic(bais)) {
                tr.runReplay(trafficSource.supplyTrafficFromSource(), trafficAccumulator);
            }
        }
        trafficAccumulator.close();
        Assertions.assertTrue(gotWarning.get());
        Assertions.assertFalse(gotAnythingElse.get());

    }

    private static String collectBytesToUtf8String(List<byte[]> bytesList) {
        return bytesList.stream()
                .map(ba -> new String(ba, StandardCharsets.UTF_8))
                .collect(Collectors.joining());
    }

}
