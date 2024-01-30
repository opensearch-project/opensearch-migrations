package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.Lombok;
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
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Slf4j
class StreamChannelConnectionCaptureSerializerTest {
    private final static String FAKE_EXCEPTION_DATA = "abcdefghijklmnop";
    private final static String FAKE_READ_PACKET_DATA = "ABCDEFGHIJKLMNOP";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "Test";
    public static final String TEST_NODE_ID_STRING = "test_node_id";

    private static int getEstimatedTrafficStreamByteSize(int readWriteEventCount, int averageDataPacketSize) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .setNanos(Instant.now().getNano())
                .build();

        return TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID_STRING)
                .setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
                .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                        .setClose(CloseObservation.newBuilder().build()).build())
                .build().getSerializedSize() +

                (((TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setWrite(WriteObservation.newBuilder().build()).build()).getSerializedSize()
                        + 2 // add 2 for subStream Overhead
                ) * readWriteEventCount)
                + averageDataPacketSize * readWriteEventCount;
    }

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

        // Create over 1MB packet
        String data = FAKE_READ_PACKET_DATA.repeat((1024 * 1024 / FAKE_READ_PACKET_DATA.length()) + 1);
        byte[] fakeDataBytes = data.getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);

        var reconstitutedTrafficStreamsList = new ArrayList<TrafficStream>();
        for (int i = 0; i < 2; ++i) {
            reconstitutedTrafficStreamsList.add(TrafficStream.parseFrom(outputBuffersList.get(i)));
        }
        reconstitutedTrafficStreamsList
                .sort(Comparator.comparingInt(StreamChannelConnectionCaptureSerializerTest::getIndexForTrafficStream));
        int totalSize = 0;
        for (int i = 0; i < 2; ++i) {
            var reconstitutedTrafficStream = reconstitutedTrafficStreamsList.get(i);
            int dataSize = reconstitutedTrafficStream.getSubStream(0).getReadSegment().getData().size();
            totalSize += dataSize;
            Assertions.assertEquals(i + 1, getIndexForTrafficStream(reconstitutedTrafficStream));
        }
        Assertions.assertEquals(fakeDataBytes.length, totalSize);
    }

    @Test
    public void testBasicDataConsistencyWhenChunking() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now();
        var packetData = FAKE_READ_PACKET_DATA.repeat(500);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking buffer to half size so as to require require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
                getEstimatedTrafficStreamByteSize(
                        1, packetBytes.length) / 2);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.add(trafficStream.getSubStream(0));
        }

        StringBuilder reconstructedData = new StringBuilder();
        for (TrafficObservation observation : observations) {
            var stringChunk = observation.getWriteSegment().getData().toStringUtf8();
            log.trace("stringChunk=" + stringChunk);
            reconstructedData.append(stringChunk);
        }
        Assertions.assertEquals(packetData, reconstructedData.toString());
    }

    @Test
    public void testCloseObservationAfterWriteWillFlushWhenSpaceNeeded() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now();
        byte[] packetBytes = FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking small buffer that can only hold one write observation and no other observations
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
                getEstimatedTrafficStreamByteSize(
                        1, packetBytes.length) -
                        CloseObservation.newBuilder().build().getSerializedSize()
        );


        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        serializer.addCloseEvent(referenceTimestamp);
        var future = serializer.flushCommitAndResetStream(true);
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
        // Picking small buffer size that can only hold two empty message
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
                getEstimatedTrafficStreamByteSize(2, 0));
        var bb = Unpooled.buffer(0);
        serializer.addWriteEvent(referenceTimestamp, bb);
        serializer.addWriteEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);
        TrafficStream reconstitutedTrafficStream = TrafficStream.parseFrom(outputBuffersList.get(0));
        Assertions.assertEquals(0, reconstitutedTrafficStream.getSubStream(0).getWrite().getData().size());
        Assertions.assertEquals(0, reconstitutedTrafficStream.getSubStream(1).getWrite().getData().size());
    }

    private static class TestException extends RuntimeException {
        public TestException(String message) {
            super(message);
        }
    }

    @Test
    public void testThatReadCanBeDeserialized() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        // these are only here as a debugging aid
        var groundTruth = makeSampleTrafficStream(referenceTimestamp);
        System.err.println("groundTruth: " + groundTruth);
        // Pasting this into `base64 -d | protoc --decode_raw` will also show the structure
        var groundTruthBytes = groundTruth.toByteArray();
        System.err.println("groundTruth Bytes: " + Base64.getEncoder().encodeToString(groundTruthBytes));

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 1024 * 1024);
        var bb = Unpooled.wrappedBuffer(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8));
        serializer.addReadEvent(referenceTimestamp, bb);
        bb.clear();
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addExceptionCaughtEvent(referenceTimestamp, new TestException(FAKE_EXCEPTION_DATA));
        serializer.addExceptionCaughtEvent(referenceTimestamp, new TestException(""));
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
        final var referenceTimestamp = Instant.now();
        var packetData = FAKE_READ_PACKET_DATA.repeat(500);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking buffer to half size so as to require require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
                getEstimatedTrafficStreamByteSize(1, packetBytes.length) / 2);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
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
        final var referenceTimestamp = Instant.now();
        var packetData = FAKE_READ_PACKET_DATA.repeat(10);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Buffer size should be large enough to hold all packetData and overhead
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 500);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
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

    @Test
    public void testAssertionErrorDuringInitializationWhenInitializeWithTooLargeId() {
        final String realNodeId = "b671d2f2-577b-414e-9eb4-8bc3e89ee182";
        final String realKafkaConnectionId = "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2";

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        assertThrows(AssertionError.class, () ->
                new StreamChannelConnectionCaptureSerializer<>("a" + realNodeId, realKafkaConnectionId,
                        new StreamManager(getEstimatedTrafficStreamByteSize(0, 0), outputBuffersCreated))
        );
    }

    @Test
    public void testInitializationWithRealIds() {
        final String realNodeId = "b671d2f2-577b-414e-9eb4-8bc3e89ee182";
        final String realKafkaConnectionId = "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2";

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        new StreamChannelConnectionCaptureSerializer<>(realNodeId, realKafkaConnectionId,
                new StreamManager(getEstimatedTrafficStreamByteSize(0, 0), outputBuffersCreated));
    }

    @AllArgsConstructor
    static class StreamManager extends OrderedStreamLifecyleManager<Void> {
        int bufferSize;
        ConcurrentLinkedQueue<ByteBuffer> outputBuffers;

        @Override
        public CodedOutputStreamHolder createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(bufferSize);
        }

        @Override
        protected CompletableFuture<Void> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
                throw new IllegalStateException("Unknown outputStreamHolder sent back to StreamManager: " +
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
                    throw Lombok.sneakyThrow(e);
                }
            }).thenApply(x -> null);
        }
    }

    private StreamChannelConnectionCaptureSerializer<Void>
    createSerializerWithTestHandler(ConcurrentLinkedQueue<ByteBuffer> outputBuffers, int bufferSize) {
        return new StreamChannelConnectionCaptureSerializer<>(TEST_NODE_ID_STRING, TEST_TRAFFIC_STREAM_ID_STRING,
                new StreamManager(bufferSize, outputBuffers));
    }
}