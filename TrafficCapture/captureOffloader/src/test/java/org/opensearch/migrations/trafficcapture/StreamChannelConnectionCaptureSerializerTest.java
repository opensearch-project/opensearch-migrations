package org.opensearch.migrations.trafficcapture;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializerTest.StreamManager.NullStreamManager;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

@Slf4j
class StreamChannelConnectionCaptureSerializerTest {

    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "Test";
    public static final String TEST_NODE_ID_STRING = "test_node_id";

    // Reference Timestamp chosen in the future with nanosecond precision resemble an upper bound on space overhead
    public static final Instant REFERENCE_TIMESTAMP = Instant.parse("2999-01-01T23:59:59.98765432Z");
    private final static String FAKE_EXCEPTION_DATA = "abcdefghijklmnop";
    private final static String FAKE_READ_PACKET_DATA = "ABCDEFGHIJKLMNOP";

    private static int getEstimatedTrafficStreamByteSize(int readWriteEventCount, int averageDataPacketSize) {
        var fixedTimestamp = Timestamp.newBuilder().setSeconds(REFERENCE_TIMESTAMP.getEpochSecond())
            .setNanos(REFERENCE_TIMESTAMP.getNano()).build();

        return TrafficStream.newBuilder().setNodeId(TEST_NODE_ID_STRING).setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
                   .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                       .setClose(CloseObservation.newBuilder().build()).build()).build().getSerializedSize() +

               (((TrafficObservation.newBuilder().setTs(fixedTimestamp).setWrite(WriteObservation.newBuilder().build())
                   .build()).getSerializedSize() + 2 // add 2 for subStream Overhead
                ) * readWriteEventCount) + averageDataPacketSize * readWriteEventCount;
    }

    private static TrafficStream makeSampleTrafficStream(Instant t) {
        var fixedTimestamp = Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
        return TrafficStream.newBuilder().setNodeId(TEST_NODE_ID_STRING).setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
            .setNumberOfThisLastChunk(1).addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp).setRead(
                    ReadObservation.newBuilder()
                        .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8))).build())
                .build()).addSubStream(
                TrafficObservation.newBuilder().setTs(fixedTimestamp).setRead(ReadObservation.newBuilder().build())
                    .build()).addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp).setConnectionException(
                ConnectionExceptionObservation.newBuilder().setMessage(FAKE_EXCEPTION_DATA).build()).build())
            .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                .setConnectionException(ConnectionExceptionObservation.newBuilder().build()).build()).addSubStream(
                TrafficObservation.newBuilder().setTs(fixedTimestamp).setEndOfMessageIndicator(
                        EndOfMessageIndication.newBuilder().setFirstLineByteLength(17).setHeadersByteLength(72).build())
                    .build()).addSubStream(
                TrafficObservation.newBuilder().setTs(fixedTimestamp).setClose(CloseObservation.newBuilder().build())
                    .build()).build();
    }

    private static int getIndexForTrafficStream(TrafficStream s) {
        return s.hasNumber() ? s.getNumber() : s.getNumberOfThisLastChunk();
    }

    @Test
    public void testLargeReadPacketIsSplit() throws IOException, ExecutionException, InterruptedException {
        var bufferSize = 1024 * 1024;
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, bufferSize);

        double minGeneratedChunks = 2.2;
        int dataRepeat = (int) Math.ceil((((double) bufferSize / FAKE_READ_PACKET_DATA.length()) * minGeneratedChunks));

        String data = FAKE_READ_PACKET_DATA.repeat(dataRepeat);
        byte[] fakeDataBytes = data.getBytes(StandardCharsets.UTF_8);

        int expectedChunks = (fakeDataBytes.length / bufferSize) + ((fakeDataBytes.length % bufferSize == 0) ? 0 : 1);

        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(REFERENCE_TIMESTAMP, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);

        var reconstitutedTrafficStreamsList = new ArrayList<TrafficStream>();
        for (int i = 0; i < expectedChunks; ++i) {
            reconstitutedTrafficStreamsList.add(TrafficStream.parseFrom(outputBuffersList.get(i)));
        }
        reconstitutedTrafficStreamsList.sort(
            Comparator.comparingInt(StreamChannelConnectionCaptureSerializerTest::getIndexForTrafficStream));
        int totalSize = 0;
        for (int i = 0; i < expectedChunks; ++i) {
            var reconstitutedTrafficStream = reconstitutedTrafficStreamsList.get(i);
            int dataSize = reconstitutedTrafficStream.getSubStream(0).getReadSegment().getData().size();
            totalSize += dataSize;
            Assertions.assertEquals(i + 1, getIndexForTrafficStream(reconstitutedTrafficStream));
            Assertions.assertTrue(dataSize <= bufferSize);
        }
        Assertions.assertEquals(fakeDataBytes.length, totalSize);
    }

    @Test
    public void testBasicDataConsistencyWhenChunking() throws IOException, ExecutionException, InterruptedException {
        var packetData = FAKE_READ_PACKET_DATA.repeat(500);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking buffer to half size to require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
            getEstimatedTrafficStreamByteSize(1, packetBytes.length) / 2);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
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
    public void testCloseObservationAfterWriteWillFlushWhenSpaceNeeded()
        throws IOException, ExecutionException, InterruptedException {
        byte[] packetBytes = FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking small buffer that can only hold one write observation and no other observations
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
            getEstimatedTrafficStreamByteSize(1, packetBytes.length) - CloseObservation.newBuilder().build()
                .getSerializedSize());

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        serializer.addCloseEvent(REFERENCE_TIMESTAMP);
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
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking small buffer size that can only hold two empty message
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, getEstimatedTrafficStreamByteSize(2, 0));
        var bb = Unpooled.buffer(0);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();
        bb.release();

        var outputBuffersList = new ArrayList<>(outputBuffersCreated);
        TrafficStream reconstitutedTrafficStream = TrafficStream.parseFrom(outputBuffersList.get(0));
        Assertions.assertEquals(0, reconstitutedTrafficStream.getSubStream(0).getWrite().getData().size());
        Assertions.assertEquals(0, reconstitutedTrafficStream.getSubStream(1).getWrite().getData().size());
    }

    @Test
    public void testWithLimitlessCodedOutputStreamHolder()
        throws IOException, ExecutionException, InterruptedException {

        var serializer = new StreamChannelConnectionCaptureSerializer<>(TEST_NODE_ID_STRING,
            TEST_TRAFFIC_STREAM_ID_STRING,
            new NullStreamManager());

        var bb = Unpooled.buffer(0);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        Assertions.assertDoesNotThrow(()-> {
                    var future = serializer.flushCommitAndResetStream(true);
                    future.get();
                });
        bb.release();
    }

    @Test
    public void testThatReadCanBeDeserialized() throws IOException, ExecutionException, InterruptedException {
        // these are only here as a debugging aid
        var groundTruth = makeSampleTrafficStream(REFERENCE_TIMESTAMP);
        System.err.println("groundTruth: " + groundTruth);
        // Pasting this into `base64 -d | protoc --decode_raw` will also show the structure
        var groundTruthBytes = groundTruth.toByteArray();
        System.err.println("groundTruth Bytes: " + Base64.getEncoder().encodeToString(groundTruthBytes));

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 1024 * 1024);
        var bb = Unpooled.wrappedBuffer(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8));
        serializer.addReadEvent(REFERENCE_TIMESTAMP, bb);
        bb.clear();
        serializer.addReadEvent(REFERENCE_TIMESTAMP, bb);
        serializer.addExceptionCaughtEvent(REFERENCE_TIMESTAMP, new TestException(FAKE_EXCEPTION_DATA));
        serializer.addExceptionCaughtEvent(REFERENCE_TIMESTAMP, new TestException(""));
        serializer.addEndOfFirstLineIndicator(17);
        serializer.addEndOfHeadersIndicator(72);
        serializer.commitEndOfHttpMessageIndicator(REFERENCE_TIMESTAMP);
        serializer.addCloseEvent(REFERENCE_TIMESTAMP);
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
    public void testEndOfSegmentsIndicationAddedWhenChunking()
        throws IOException, ExecutionException, InterruptedException {
        var packetData = FAKE_READ_PACKET_DATA.repeat(500);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking buffer to half size to require chunking
        var serializer = createSerializerWithTestHandler(outputBuffersCreated,
            getEstimatedTrafficStreamByteSize(1, packetBytes.length) / 2);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
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
    public void testEndOfSegmentsIndicationNotAddedWhenNotChunking()
        throws IOException, ExecutionException, InterruptedException {
        var packetData = FAKE_READ_PACKET_DATA.repeat(10);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Buffer size should be large enough to hold all packetData and overhead
        var serializer = createSerializerWithTestHandler(outputBuffersCreated, 500);

        var bb = Unpooled.wrappedBuffer(packetBytes);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
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

        // Prepending "a" to a realNodeId to create a larger than expected id to trigger failure
        final String tooLargeNodeId = 'a' + realNodeId;

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        assertThrows(AssertionError.class,
            () -> new StreamChannelConnectionCaptureSerializer<>("a" + tooLargeNodeId, realKafkaConnectionId,
                new StreamManager(getEstimatedTrafficStreamByteSize(0, 0), outputBuffersCreated)));
    }

    @Test
    public void testInitializationWithRealIds() {
        final String realNodeId = "b671d2f2-577b-414e-9eb4-8bc3e89ee182";
        final String realKafkaConnectionId = "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2";

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        new StreamChannelConnectionCaptureSerializer<>(realNodeId, realKafkaConnectionId,
            new StreamManager(getEstimatedTrafficStreamByteSize(0, 0), outputBuffersCreated));
    }

    private StreamChannelConnectionCaptureSerializer<Void> createSerializerWithTestHandler(
        ConcurrentLinkedQueue<ByteBuffer> outputBuffers, int bufferSize) {
        return new StreamChannelConnectionCaptureSerializer<>(TEST_NODE_ID_STRING, TEST_TRAFFIC_STREAM_ID_STRING,
            new StreamManager(bufferSize, outputBuffers));
    }

    private static class TestException extends RuntimeException {

        public TestException(String message) {
            super(message);
        }
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
                throw new IllegalStateException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder);
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            log.atTrace().log(() -> "Getting ready to flush for " + osh);
            log.atTrace().log(() -> "Bytes written so far... " + StandardCharsets.UTF_8.decode(osh.getByteBuffer().duplicate()));

            return CompletableFuture.runAsync(() -> {
                try {
                    osh.getOutputStream().flush();
                    log.atTrace().log(() -> "Just flushed for " + osh.getOutputStream());
                    var bb = osh.getByteBuffer();
                    bb.position(0);
                    var bytesWritten = osh.getOutputStream().getTotalBytesWritten();
                    bb.limit(bytesWritten);
                    log.atTrace().log(() -> "Adding " + StandardCharsets.UTF_8.decode(bb.duplicate()));
                    outputBuffers.add(bb);
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            }).thenApply(x -> null);
        }

        static class NullStreamManager implements StreamLifecycleManager<CodedOutputStreamHolder> {

            @Override
            public CodedOutputStreamHolder createStream() {
                return new CodedOutputStreamHolder() {
                    final CodedOutputStream nullOutputStream = CodedOutputStream.newInstance(
                        OutputStream.nullOutputStream());

                    @Override
                    public int getOutputStreamBytesLimit() {
                        return -1;
                    }

                    @Override
                    public @NonNull CodedOutputStream getOutputStream() {
                        return nullOutputStream;
                    }
                };
            }

            @Override
            public CompletableFuture<CodedOutputStreamHolder> closeStream(CodedOutputStreamHolder outputStreamHolder,
                int index) {
                return CompletableFuture.completedFuture(null);
            }

        }
    }
}