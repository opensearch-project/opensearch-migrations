package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
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
@WrapWithNettyLeakDetection
class TrafficReplayerTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";
    private static String TEST_TRAFFIC_STREAM_ID_STRING = "testId";
    private static String FAKE_READ_PACKET_DATA = "Useless packet data for test";
    private static String FAKE_EXCEPTION_DATA = "Mock Exception Message for testing";

    private static TrafficStream makeTrafficStream(Instant t, int trafficChunkNumber) {
        Timestamp fixedTimestamp = getProtobufTimestamp(t);
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

    private static Timestamp getProtobufTimestamp(Instant t) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        return fixedTimestamp;
    }

    @Test
    public void testDelimitedDeserializer() throws Exception {
        final Instant timestamp = Instant.now();
        byte[] serializedChunks = synthesizeTrafficStreamsIntoByteArray(timestamp, 3);
        try (var bais = new ByteArrayInputStream(serializedChunks)) {
            AtomicInteger counter = new AtomicInteger(0);
            var allMatch = new AtomicBoolean(true);
            try (var trafficProducer = new InputStreamOfTraffic(bais)) {
                while (true) {
                    trafficProducer.readNextTrafficStreamChunk().get().stream().forEach(ts->{
                        var i = counter.incrementAndGet();
                        var expectedStream = makeTrafficStream(timestamp.plus(i - 1, ChronoUnit.SECONDS), i);
                        var isEqual = ts.getStream().equals(expectedStream);
                        if (!isEqual) {
                            log.error("Expected trafficStream: " + expectedStream);
                            log.error("Observed trafficStream: " + ts);
                        }
                        allMatch.set(allMatch.get() && isEqual);
                    });
                }
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof EOFException)) {
                    throw e;
                }
            }
            Assertions.assertTrue(allMatch.get());
        }
    }

    static byte[] synthesizeTrafficStreamsIntoByteArray(Instant timestamp, int numStreams) throws IOException {
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
    public void testReader() throws Exception {
        var tr = new TrafficReplayer(new URI("http://localhost:9200"), null, null, false);
        List<List<byte[]>> byteArrays = new ArrayList<>();
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30), null,
                        new AccumulationCallbacks() {
                            @Override
                            public void onRequestReceived(UniqueReplayerRequestKey id, HttpMessageAndTimestamp request) {
                                var bytesList = request.stream().collect(Collectors.toList());
                                byteArrays.add(bytesList);
                                Assertions.assertEquals(FAKE_READ_PACKET_DATA, collectBytesToUtf8String(bytesList));
                            }

                            @Override
                            public void onFullDataReceived(UniqueReplayerRequestKey key, RequestResponsePacketPair fullPair) {
                                var responseBytes = fullPair.responseData.packetBytes.stream().collect(Collectors.toList());
                                Assertions.assertEquals(FAKE_READ_PACKET_DATA, collectBytesToUtf8String(responseBytes));
                            }

                            @Override
                            public void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                                                List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {}

                            @Override
                            public void onConnectionClose(ISourceTrafficChannelKey key, int channelInteractionNumber,
                                                          RequestResponsePacketPair.ReconstructionStatus status,
                                                          Instant when,
                                                          List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
                            }

                            @Override public void onTrafficStreamIgnored(@NonNull ITrafficStreamKey tsk) {}
                        });
        var bytes = synthesizeTrafficStreamsIntoByteArray(Instant.now(), 1);

        try (var bais = new ByteArrayInputStream(bytes)) {
            try (var trafficSource = new InputStreamOfTraffic(bais)) {
                tr.pullCaptureFromSourceToAccumulator(trafficSource, trafficAccumulator);
            }
        }
        Assertions.assertEquals(1, byteArrays.size());
        Assertions.assertTrue(byteArrays.stream().allMatch(ba->ba.size()==2));
    }

    @Test
    public void testCapturedReadsAfterCloseAreHandledAsNew() throws Exception {
        var tr = new TrafficReplayer(new URI("http://localhost:9200"), null, null, false);
        List<List<byte[]>> byteArrays = new ArrayList<>();
        var remainingAccumulations = new AtomicInteger();
        CapturedTrafficToHttpTransactionAccumulator trafficAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(Duration.ofSeconds(30),
                        "change the minTimeout argument to the c'tor of " +
                                "CapturedTrafficToHttpTransactionAccumulator that's being used in this unit test!",
                        new AccumulationCallbacks() {
                            @Override
                            public void onRequestReceived(UniqueReplayerRequestKey id, HttpMessageAndTimestamp request) {
                                var bytesList = request.stream().collect(Collectors.toList());
                                byteArrays.add(bytesList);
                                Assertions.assertEquals(FAKE_READ_PACKET_DATA, collectBytesToUtf8String(bytesList));
                            }

                            @Override
                            public void onFullDataReceived(UniqueReplayerRequestKey key, RequestResponsePacketPair fullPair) {
                                var responseBytes = fullPair.responseData.packetBytes.stream().collect(Collectors.toList());
                                Assertions.assertEquals(FAKE_READ_PACKET_DATA, collectBytesToUtf8String(responseBytes));
                            }

                            @Override
                            public void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                                                List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {}

                            @Override
                            public void onConnectionClose(ISourceTrafficChannelKey key, int channelInteractionNumber,
                                                          RequestResponsePacketPair.ReconstructionStatus status,
                                                          Instant when,
                                                          List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
                            }
                            @Override public void onTrafficStreamIgnored(@NonNull ITrafficStreamKey tsk) {}
                        }
                );
        byte[] serializedChunks;
        try (var baos = new ByteArrayOutputStream()) {
            makeTrafficStream(Instant.now(), 1).writeDelimitedTo(baos);
            TrafficStream.newBuilder()
                    .setNumberOfThisLastChunk(2)
                    .setNodeId(TEST_NODE_ID_STRING)
                    .setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
                    .addSubStream(TrafficObservation.newBuilder()
                            .setTs(getProtobufTimestamp(Instant.now()))
                            .setClose(CloseObservation.getDefaultInstance())
                            .build())
                    .build()
                    .writeDelimitedTo(baos);
            makeTrafficStream(Instant.now(), 3).writeDelimitedTo(baos);
            serializedChunks = baos.toByteArray();
        }

        try (var bais = new ByteArrayInputStream(serializedChunks)) {
            try (var trafficSource = new InputStreamOfTraffic(bais)) {
                tr.pullCaptureFromSourceToAccumulator(trafficSource, trafficAccumulator);
            }
        }
        trafficAccumulator.close();
        Assertions.assertEquals(2, byteArrays.size());
        Assertions.assertTrue(byteArrays.stream().allMatch(ba->ba.size()==2));
        Assertions.assertEquals(0, remainingAccumulations.get());
    }

    private static String collectBytesToUtf8String(List<byte[]> bytesList) {
        return bytesList.stream()
                .map(ba -> new String(ba, StandardCharsets.UTF_8))
                .collect(Collectors.joining());
    }
}
