package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
class StreamChannelConnectionCaptureSerializerTest {
    private final static String FAKE_EXCEPTION_DATA = "abcdefghijklmnop";
    private final static String FAKE_READ_PACKET_DATA = "ABCDEFGHIJKLMNOP";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "Test";
    public static final String TEST_NODE_ID_STRING = "test_node_id";


    private static TrafficStream makeSampleTrafficStream(Instant t) {
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
                        .setRead(ReadObservation.newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setConnectionException(ConnectionExceptionObservation.newBuilder()
                                .setMessage(FAKE_EXCEPTION_DATA)
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setConnectionException(ConnectionExceptionObservation.newBuilder()
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                                .setFirstLineByteLength(17)
                                .setHeadersByteLength(72)
                                .build())
                        .build())
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setClose(CloseObservation.newBuilder().build())
                        .build())
                .build();
    }

    private static int getIndexForTrafficStream(TrafficStream s) {
        return s.hasNumber() ? s.getNumber() : s.getNumberOfThisLastChunk();
    }

    @Test
    public void testLargeReadPacketIsSplit() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
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

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);

        var reconstitutedTrafficStreamsList = new ArrayList<TrafficStream>();
        for (int i=0; i<2; ++i) {
            reconstitutedTrafficStreamsList.add(TrafficStream.parseFrom(outputBuffersList.get(i)));
        }
        reconstitutedTrafficStreamsList
                .sort(Comparator.comparingInt(StreamChannelConnectionCaptureSerializerTest::getIndexForTrafficStream));
        int totalSize = 0;
        for (int i=0; i<2; ++i) {
            var reconstitutedTrafficStream = reconstitutedTrafficStreamsList.get(i);
            int dataSize = reconstitutedTrafficStream.getSubStream(0).getReadSegment().getData().size();
            totalSize += dataSize;
            Assertions.assertEquals(i + 1, reconstitutedTrafficStream.getSubStream(0).getReadSegment().getCount());
            Assertions.assertEquals(i + 1, getIndexForTrafficStream(reconstitutedTrafficStream));
        }
        Assertions.assertEquals(fakeDataBytes.length, totalSize);
    }

    @Test
    public void testBasicDataConsistencyWhenChunking() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.ofEpochMilli(1686593191*1000);
        String packetData = "";
        for (int i = 0; i < 500; i++) {
            packetData += "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        }
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Arbitrarily picking small buffer that can hold the overhead TrafficStream bytes as well as some
        // data bytes but not all the data bytes and require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 55);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.add(trafficStream.getSubStream(0));
        }

        // Sort observations, as buffers in our close handler are written async and may not be executed in order
        Collections.sort(observations, Comparator.comparingInt(observation -> observation.getWriteSegment().getCount()));
        String reconstructedData = "";
        for (TrafficObservation observation : observations) {
            var stringChunk = observation.getWriteSegment().getData().toStringUtf8();
            log.trace("stringChunk=" + stringChunk);
            reconstructedData += stringChunk;
        }
        Assertions.assertEquals(packetData, reconstructedData);
    }

    @Test
    public void testCloseObservationAfterWriteWillFlushWhenSpaceNeeded() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.ofEpochMilli(1686593191*1000);
        String packetData = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Arbitrarily picking small buffer that can only hold one write observation and no other observations
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 85);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        serializer.addCloseEvent(referenceTimestamp);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        Assertions.assertEquals(2, outputBuffersCreated.size());
        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.addAll(trafficStream.getSubStreamList());
        }
        Assertions.assertEquals(2, observations.size());
        Assertions.assertTrue(observations.get(0).hasWrite());
        Assertions.assertTrue(observations.get(1).hasClose());
    }

    @Test
    public void testEmptyPacketIsHandledForSmallCodedOutputStream()
        throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Arbitrarily picking small buffer size that can only hold one empty message
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
                TEST_NODE_ID_STRING.length() + 40);
        var bb = Unpooled.buffer(0);
        serializer.addWriteEvent(referenceTimestamp, bb);
        serializer.addWriteEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);
        TrafficStream reconstitutedTrafficStreamPart1 = TrafficStream.parseFrom(outputBuffersList.get(0));
        Assertions.assertEquals(0, reconstitutedTrafficStreamPart1.getSubStream(0).getWrite().getData().size());
        TrafficStream reconstitutedTrafficStreamPart2 = TrafficStream.parseFrom(outputBuffersList.get(1));
        Assertions.assertEquals(0, reconstitutedTrafficStreamPart2.getSubStream(0).getWrite().getData().size());
    }

    @Test
    public void testThatReadCanBeDeserialized() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        // these are only here as a debugging aid
        var groundTruth = makeSampleTrafficStream(referenceTimestamp);
        System.err.println("groundTruth: "+groundTruth);
        // Pasting this into `base64 -d | protoc --decode_raw` will also show the structure
        var groundTruthBytes = groundTruth.toByteArray();
        System.err.println("groundTruth Bytes: "+Base64.getEncoder().encodeToString(groundTruthBytes));

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
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
        serializer.addCloseEvent(referenceTimestamp);
        serializer.flushCommitAndResetStream(true).get();
        bb.release();

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);
        Assertions.assertEquals(1, outputBuffersCreated.size());
        var onlyBuffer = outputBuffersList.get(0);
        var reconstitutedTrafficStream = TrafficStream.parseFrom(onlyBuffer);
        Assertions.assertEquals(TEST_TRAFFIC_STREAM_ID_STRING, reconstitutedTrafficStream.getConnectionId());
        Assertions.assertEquals(TEST_NODE_ID_STRING, reconstitutedTrafficStream.getNodeId());
        Assertions.assertEquals(6, reconstitutedTrafficStream.getSubStreamCount());
        Assertions.assertEquals(1, reconstitutedTrafficStream.getNumberOfThisLastChunk());
        Assertions.assertFalse(reconstitutedTrafficStream.hasNumber());

        Assertions.assertEquals(groundTruth, reconstitutedTrafficStream);
    }

    @Test
    public void testEndOfSegmentsIndicationAddedWhenChunking() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.ofEpochMilli(1686593191*1000);
        String packetData = "";
        for (int i = 0; i < 500; i++) {
            packetData += "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        }
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Arbitrarily picking small buffer that can hold the overhead TrafficStream bytes as well as some
        // data bytes but not all the data bytes and require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 85);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.addAll(trafficStream.getSubStreamList());
        }

        int foundEndOfSegments = 0;
        for (TrafficObservation observation : observations) {
            if (observation.hasSegmentEnd()) {
                foundEndOfSegments++;
                EndOfSegmentsIndication endOfSegment = observation.getSegmentEnd();
                Assertions.assertEquals(EndOfSegmentsIndication.getDefaultInstance(), endOfSegment);
            }
        }
        Assertions.assertEquals(1, foundEndOfSegments);
    }

    @Test
    public void testEndOfSegmentsIndicationNotAddedWhenNotChunking() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.ofEpochMilli(1686593191*1000);
        String packetData = "";
        for (int i = 0; i < 10; i++) {
            packetData += "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        }
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Buffer size should be large enough to hold all packetData and overhead
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 500);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        CompletableFuture future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.addAll(trafficStream.getSubStreamList());
        }

        int foundEndOfSegments = 0;
        for (TrafficObservation observation : observations) {
            if (observation.hasSegmentEnd()) {
                foundEndOfSegments++;
            }
        }
        Assertions.assertEquals(0, foundEndOfSegments);
    }

    @AllArgsConstructor
    class StreamManager extends OrderedStreamLifecyleManager {
        int bufferSize;
        ConcurrentLinkedQueue<ByteBuffer> outputBuffers;

        @Override
        protected CodedOutputStreamHolder createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(bufferSize);
        }

        @Override
        protected CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
                throw new RuntimeException("Unknown outputStreamHolder sent back to StreamManager: " +
                        outputStreamHolder);
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            log.trace("Getting ready to flush for " + osh);
            log.trace("Bytes written so far... " + StandardCharsets.UTF_8.decode(osh.getByteBuffer().duplicate()));

            return CompletableFuture.runAsync(() -> {
                try {
                    osh.getOutputStream().flush();
                    log.trace("Just flushed for " + osh.getOutputStream());
                    var bb = osh.getByteBuffer();
                    bb.position(0);
                    var bytesWritten = osh.getOutputStream().getTotalBytesWritten();
                    bb.limit(bytesWritten);
                    log.trace("Adding " + StandardCharsets.UTF_8.decode(bb.duplicate()));
                    outputBuffers.add(bb);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).thenApply(x->null);
        }
    }

    private StreamChannelConnectionCaptureSerializer
    createSerializerWithTestHandler(ConcurrentLinkedQueue<ByteBuffer> outputBuffers, int bufferSize) {
        return new StreamChannelConnectionCaptureSerializer(TEST_NODE_ID_STRING, TEST_TRAFFIC_STREAM_ID_STRING,
            new StreamManager(bufferSize, outputBuffers));
    }

    private static String mapToKeyStrings(ConcurrentHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteBuffersMap) {
        return codedStreamToByteBuffersMap.keySet().stream().map(k -> k.toString()).collect(Collectors.joining(","));
    }
}