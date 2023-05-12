package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndicator;
import org.opensearch.migrations.trafficcapture.protos.Exception;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

class StreamChannelConnectionCaptureSerializerTest {
    private final static String FAKE_EXCEPTION_DATA = "abcdefghijklmnop";
    private final static String FAKE_READ_PACKET_DATA = "ABCDEFGHIJKLMNOP";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "Test";

    private static TrafficStream makeEmptyTrafficStream(Instant t) {
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
    public void testLargeReadPacketIsSplit() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        List<ByteBuffer> outputBuffersCreated = new ArrayList<>();
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 1024 * 1024);

        StringBuilder sb = new StringBuilder();
        // Create over 1MB packet
        for (int i = 0; i < 15000; i++) {
            sb.append("{ \"create\": { \"_index\": \"office-index\" } }\n{ \"title\": \"Malone's Cones\", \"year\": 2013 }\n");
        }
        byte[] fakeDataBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        TrafficStream reconstitutedTrafficStreamPart1 = TrafficStream.parseFrom(outputBuffersCreated.get(0));
        int part1DataSize = reconstitutedTrafficStreamPart1.getSubStream(0).getReadSegment().getData().size();
        Assertions.assertEquals(1, reconstitutedTrafficStreamPart1.getSubStream(0).getReadSegment().getCount());
        Assertions.assertEquals(1, reconstitutedTrafficStreamPart1.getNumber());
        TrafficStream reconstitutedTrafficStreamPart2 = TrafficStream.parseFrom(outputBuffersCreated.get(1));
        int part2DataSize = reconstitutedTrafficStreamPart2.getSubStream(0).getReadSegment().getData().size();
        Assertions.assertEquals(2, reconstitutedTrafficStreamPart2.getSubStream(0).getReadSegment().getCount());
        Assertions.assertEquals(2, reconstitutedTrafficStreamPart2.getNumberOfThisLastChunk());
        Assertions.assertEquals(fakeDataBytes.length, part1DataSize + part2DataSize);
    }

    @Test
    public void testBasicDataConsistencyWhenChunking() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        String packetData = "";
        for (int i = 0; i < 5; i++) {
            packetData += "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        }
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        List<ByteBuffer> outputBuffersCreated = new ArrayList<>();
        // Arbitrarily picking small buffer that can hold the overhead TrafficStream bytes as well as some
        // data bytes but not all the data bytes and require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 50);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            observations.add(TrafficStream.parseFrom(buffer).getSubStream(0));
        }

        // Sort observations, as buffers in our close handler are written async and may not be executed in order
        Collections.sort(observations, Comparator.comparingInt(observation -> observation.getWriteSegment().getCount()));
        String reconstructedData = "";
        for (TrafficObservation observation : observations) {
            reconstructedData += observation.getWriteSegment().getData().toStringUtf8();
        }
        Assertions.assertEquals(packetData, reconstructedData);
    }

    @Test
    public void testEmptyPacketIsHandledForSmallCodedOutputStream()
        throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        List<ByteBuffer> outputBuffersCreated = new ArrayList<>();
        // Arbitrarily picking small buffer size that can only hold one empty message
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 40);
        var bb = Unpooled.buffer(0);
        serializer.addWriteEvent(referenceTimestamp, bb);
        serializer.addWriteEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        TrafficStream reconstitutedTrafficStreamPart1 = TrafficStream.parseFrom(outputBuffersCreated.get(0));
        Assertions.assertEquals(0, reconstitutedTrafficStreamPart1.getSubStream(0).getWrite().getData().size());
        TrafficStream reconstitutedTrafficStreamPart2 = TrafficStream.parseFrom(outputBuffersCreated.get(1));
        Assertions.assertEquals(0, reconstitutedTrafficStreamPart2.getSubStream(0).getWrite().getData().size());
    }

    @Test
    public void testThatReadCanBeDeserialized() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        // these are only here as a debugging aid
        var groundTruth = makeEmptyTrafficStream(referenceTimestamp);
        System.err.println("groundTruth: "+groundTruth);
        // Pasting this into `base64 -d | protoc --decode_raw` will also show the structure
        var groundTruthBytes = groundTruth.toByteArray();
        System.err.println("groundTruth Bytes: "+Base64.getEncoder().encodeToString(groundTruthBytes));

        List<ByteBuffer> outputBuffersCreated = new ArrayList<>();
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 1024 * 1024);
        var bb = Unpooled.wrappedBuffer(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8));
        serializer.addReadEvent(referenceTimestamp, bb);
        bb.clear();
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addExceptionCaughtEvent(referenceTimestamp, new RuntimeException(FAKE_EXCEPTION_DATA));
        serializer.addExceptionCaughtEvent(referenceTimestamp, new RuntimeException(""));
        serializer.addEndOfFirstLineIndicator(17);
        serializer.addEndOfHeadersIndicator(72);
        serializer.commitEndOfHttpMessageIndicator(referenceTimestamp);
        serializer.flushCommitAndResetStream(true).get();
        bb.release();
        Assertions.assertEquals(1, outputBuffersCreated.size());
        var onlyBuffer = outputBuffersCreated.get(0);
        var reconstitutedTrafficStream = TrafficStream.parseFrom(onlyBuffer);
        Assertions.assertEquals(TEST_TRAFFIC_STREAM_ID_STRING, reconstitutedTrafficStream.getId());
        Assertions.assertEquals(5, reconstitutedTrafficStream.getSubStreamCount());
        Assertions.assertEquals(1, reconstitutedTrafficStream.getNumberOfThisLastChunk());
        Assertions.assertFalse(reconstitutedTrafficStream.hasNumber());

        Assertions.assertEquals(groundTruth, reconstitutedTrafficStream);
    }

    private StreamChannelConnectionCaptureSerializer createSerializerWithTestHandler(List<ByteBuffer> outputBuffers, int bufferSize) throws IOException {
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteBuffersMap = new WeakHashMap<>();
        CompletableFuture[] singleAggregateCfRef = new CompletableFuture[1];
        singleAggregateCfRef[0] = CompletableFuture.completedFuture(null);
        return new StreamChannelConnectionCaptureSerializer(TEST_TRAFFIC_STREAM_ID_STRING, 1,
            () -> {
                ByteBuffer bytes = ByteBuffer.allocate(bufferSize);
                var rval = CodedOutputStream.newInstance(bytes);
                codedStreamToByteBuffersMap.put(rval, bytes);
                return rval;
            },
            (codedOutputStream) -> {
                CompletableFuture cf = CompletableFuture.runAsync(() -> {
                    try {
                        codedOutputStream.flush();
                        var bb = codedStreamToByteBuffersMap.get(codedOutputStream);
                        bb.position(0);
                        var bytesWritten = codedOutputStream.getTotalBytesWritten();
                        bb.limit(bytesWritten);
                        outputBuffers.add(bb);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                singleAggregateCfRef[0] = CompletableFuture.allOf(singleAggregateCfRef[0], cf);
                return singleAggregateCfRef[0];
            }
        );
    }
}