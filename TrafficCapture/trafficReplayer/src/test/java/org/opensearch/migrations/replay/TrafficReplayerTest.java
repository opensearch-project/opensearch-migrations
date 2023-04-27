package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndicator;
import org.opensearch.migrations.trafficcapture.protos.Exception;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

class TrafficReplayerTest {

    private static String TEST_TRAFFIC_STREAM_ID_STRING = "testId";
    private static String FAKE_READ_PACKET_DATA = "Useless packet data for test";
    private static String FAKE_EXCEPTION_DATA = "Mock Exception Message for testing";

    private static TrafficStream makeTrafficStream(Instant t) {
        return TrafficStream.newBuilder()
                .setId(TEST_TRAFFIC_STREAM_ID_STRING)
                .setNumberOfThisLastChunk(1)
                .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder()
                                .setSeconds(t.getEpochSecond())
                                .setNanos(t.getNano())
                                .build())
                        .setRead(Read.newBuilder()
                                .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder()
                                .setSeconds(t.getEpochSecond())
                                .setNanos(t.getNano())
                                .build())
                        .setRead(Read.newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder()
                                .setSeconds(t.getEpochSecond())
                                .setNanos(t.getNano())
                                .build())
                        .setException(Exception.newBuilder()
                                .setMessage(FAKE_EXCEPTION_DATA)
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder()
                                .setSeconds(t.getEpochSecond())
                                .setNanos(t.getNano())
                                .build())
                        .setException(Exception.newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder()
                                .setSeconds(t.getEpochSecond())
                                .setNanos(t.getNano())
                                .build())
                        .setEndOfMessageIndicator(EndOfMessageIndicator.newBuilder()
                                .setFirstLineByteLength(17)
                                .setHeadersByteLength(72)
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
            Assertions.assertTrue(TrafficReplayer.getLogEntriesFromInputStream(bais).stream()
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
        var tr = new TrafficReplayer(new URI("http://localhost:9200"));
        List<List<byte[]>> byteArrays = new ArrayList<>();
        TrafficReplayer.ReplayEngine re = new TrafficReplayer.ReplayEngine(rrpp -> {
            var bytesList = rrpp.getRequestDataStream().collect(Collectors.toList());
            byteArrays.add(bytesList);
            Assertions.assertEquals(FAKE_READ_PACKET_DATA,
                    bytesList.stream()
                            .map(ba->new String(ba, StandardCharsets.UTF_8))
                            .collect(Collectors.joining()));
        });
        var bytes = synthesizeTrafficStreamsIntoByteArray(Instant.now(), 3);

        try (var bais = new ByteArrayInputStream(bytes)) {
            try (var cssw = TrafficReplayer.getLogEntriesFromInputStream(bais)) {
                tr.runReplay(cssw.stream(), re);
            }
        }
        Assertions.assertEquals(3, byteArrays.size());
        Assertions.assertEquals(1, byteArrays.get(0).size());
    }
}