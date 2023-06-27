package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class TrafficReplayerTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";
    private static String TEST_TRAFFIC_STREAM_ID_STRING = "testId";
    private static String FAKE_READ_PACKET_DATA = "Useless packet data for test";
    private static String FAKE_EXCEPTION_DATA = "Mock Exception Message for testing";

    private static TrafficStream makeTrafficStream(Instant t) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        return TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID_STRING)
                .setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
                .setNumberOfThisLastChunk(1)
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
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                                .build())
                        .build())
                .build();
    }

    @Test
    public void testDelimitedDeserializer() throws IOException {
        final Instant timestamp = Instant.now();
        byte[] serializedChunks = synthesizeTrafficStreamsIntoByteArray(timestamp, 3);
        try (var bais = new ByteArrayInputStream(serializedChunks)) {
            AtomicInteger counter = new AtomicInteger();
            Assertions.assertTrue(CloseableTrafficStreamWrapper.getCaptureEntriesFromInputStream(bais).stream()
                    .allMatch(ts->ts.equals(makeTrafficStream(timestamp.plus(counter.getAndIncrement(),
                            ChronoUnit.SECONDS)))));
        }
    }

    private byte[] synthesizeTrafficStreamsIntoByteArray(Instant timestamp, int numStreams) throws IOException {
        byte[] serializedChunks;
        try (var baos = new ByteArrayOutputStream()) {
            for (int i=0; i<numStreams; ++i) {
                makeTrafficStream(timestamp.plus(i, ChronoUnit.SECONDS)).writeDelimitedTo(baos);
            }
            serializedChunks = baos.toByteArray();
        }
        return serializedChunks;
    }

    @Test
    public void testReader() throws IOException, URISyntaxException, InterruptedException {
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
            try (var cssw = CloseableTrafficStreamWrapper.getCaptureEntriesFromInputStream(bais)) {
                tr.runReplay(cssw.stream(), trafficAccumulator);
            }
        }
        Assertions.assertEquals(3, byteArrays.size());
        Assertions.assertTrue(byteArrays.stream().allMatch(ba->ba.size()==2));
    }

    private static String collectBytesToUtf8String(List<byte[]> bytesList) {
        return bytesList.stream()
                .map(ba -> new String(ba, StandardCharsets.UTF_8))
                .collect(Collectors.joining());
    }

}