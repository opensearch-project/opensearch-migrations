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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class TrafficReplayerTest {

    private static String TEST_TRAFFIC_STREAM_ID_STRING = "testId";
    private static String FAKE_READ_PACKET_DATA = "Useless packet data for test";
    private static String FAKE_EXCEPTION_DATA = "Mock Exception Message for testing";

    private static TrafficStream makeTrafficStream(Instant t) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        return TrafficStream.newBuilder()
                .setId(TEST_TRAFFIC_STREAM_ID_STRING)
                .setNumberOfThisLastChunk(1)
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
                        .setException(ConnectionExceptionObservation.newBuilder().build().newBuilder()
                                .setMessage(FAKE_EXCEPTION_DATA)
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setException(ConnectionExceptionObservation.newBuilder().build().newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
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
        var tr = new TrafficReplayer(new URI("http://localhost:9200"));
        List<List<byte[]>> byteArrays = new ArrayList<>();
        ReplayEngine re = new ReplayEngine(rrpp -> {
            var bytesList = rrpp.getRequestDataStream().collect(Collectors.toList());
            byteArrays.add(bytesList);
            Assertions.assertEquals(FAKE_READ_PACKET_DATA,
                    bytesList.stream()
                            .map(ba->new String(ba, StandardCharsets.UTF_8))
                            .collect(Collectors.joining()));
        });
        var bytes = synthesizeTrafficStreamsIntoByteArray(Instant.now(), 3);

        try (var bais = new ByteArrayInputStream(bytes)) {
            try (var cssw = CloseableTrafficStreamWrapper.getCaptureEntriesFromInputStream(bais)) {
                tr.runReplay(cssw.stream(), re);
            }
        }
        Assertions.assertEquals(3, byteArrays.size());
        Assertions.assertEquals(1, byteArrays.get(0).size());
    }

    @Test
    public void testTransformer() throws Exception {
        var referenceStringBuilder = new StringBuilder();
        var numFinalizations = new AtomicInteger();
        // mock object.  values don't matter at all - not what we're testing
        var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null);
        var transformingHandler = new HttpMessageTransformerHandler(new IPacketToHttpHandler() {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            @Override
            public void consumeBytes(byte[] nextRequestPacket) throws InvalidHttpStateException, IOException {
                byteArrayOutputStream.write(nextRequestPacket);
            }

            @Override
            public void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback) throws InvalidHttpStateException, IOException {
                numFinalizations.incrementAndGet();
                var bytes = byteArrayOutputStream.toByteArray();
                Assertions.assertEquals(referenceStringBuilder.toString(), new String(bytes, StandardCharsets.UTF_8));
                onResponseFinishedCallback.accept(dummyAggregatedResponse);
            }
        });

        Random r = new Random(2);

        for (int i=0; i<3; ++i) {
            String s = r.ints(r.nextInt(10), 'A', 'Z')
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            writeStringToBoth(s, referenceStringBuilder, transformingHandler);
        }
        var innermostFinalizeCallCount = new AtomicInteger();
        transformingHandler.finalizeRequest(x-> {
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
            Assertions.assertEquals(dummyAggregatedResponse, x);
        });
        Assertions.assertEquals(1, innermostFinalizeCallCount.get());
        Assertions.assertEquals(1, numFinalizations.get());
    }

    private static void writeStringToBoth(String s, StringBuilder referenceStringBuilder,
                                          HttpMessageTransformerHandler transformingHandler) throws Exception {
        referenceStringBuilder.append(s);
        transformingHandler.consumeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}