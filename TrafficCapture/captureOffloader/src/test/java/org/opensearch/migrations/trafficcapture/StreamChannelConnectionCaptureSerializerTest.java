package org.opensearch.migrations.trafficcapture;

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

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializerTest.StreamManager.NullStreamManager;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.trafficcapture.protos.WriteSegmentObservation;

import io.netty.buffer.Unpooled;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 4)
class StreamChannelConnectionCaptureSerializerTest {

    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "Test";
    public static final String TEST_NODE_ID_STRING = "test_node_id";

    // Reference Timestamp chosen in the future with nanosecond precision resemble an upper bound on space overhead
    public static final Instant REFERENCE_TIMESTAMP = Instant.parse("2999-01-01T23:59:59.98765432Z");
    private final static String FAKE_EXCEPTION_DATA = "abcdefghijklmnop";
    private final static String FAKE_READ_PACKET_DATA = "ABCDEFGHIJKLMNOP";

    private static int getEstimatedTrafficStreamByteSize(int readWriteEventCount, int averageDataPacketSize) {
        var fixedTimestamp = Timestamp.newBuilder()
            .setSeconds(REFERENCE_TIMESTAMP.getEpochSecond())
            .setNanos(REFERENCE_TIMESTAMP.getNano())
            .build();

        return TrafficStream.newBuilder()
            .setNodeId(TEST_NODE_ID_STRING)
            .setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setClose(CloseObservation.newBuilder().build())
                    .build()
            )
            .build()
            .getSerializedSize() +

            (((TrafficObservation.newBuilder()
                .setTs(fixedTimestamp)
                .setWrite(WriteObservation.newBuilder().build())
                .build()).getSerializedSize() + 2 // add 2 for subStream Overhead
            ) * readWriteEventCount) + averageDataPacketSize * readWriteEventCount;
    }

    private static TrafficStream makeSampleTrafficStream(Instant t) {
        var fixedTimestamp = Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
        return TrafficStream.newBuilder()
            .setNodeId(TEST_NODE_ID_STRING)
            .setConnectionId(TEST_TRAFFIC_STREAM_ID_STRING)
            .setNumberOfThisLastChunk(1)
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setRead(
                        ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                            .build()
                    )
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setRead(ReadObservation.newBuilder().build())
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setConnectionException(
                        ConnectionExceptionObservation.newBuilder().setMessage(FAKE_EXCEPTION_DATA).build()
                    )
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setConnectionException(ConnectionExceptionObservation.newBuilder().build())
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setEndOfMessageIndicator(
                        EndOfMessageIndication.newBuilder().setFirstLineByteLength(17).setHeadersByteLength(72).build()
                    )
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setClose(CloseObservation.newBuilder().build())
                    .build()
            )
            .build();
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
            Comparator.comparingInt(StreamChannelConnectionCaptureSerializerTest::getIndexForTrafficStream)
        );
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
        var serializer = createSerializerWithTestHandler(
            outputBuffersCreated,
            getEstimatedTrafficStreamByteSize(1, packetBytes.length) / 2
        );

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
    public void testWriteObservationWithSpaceForOneByteAtATime() throws IOException, ExecutionException,
        InterruptedException {
        var packetData = FAKE_READ_PACKET_DATA.repeat(5);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var numberOfChunks = packetBytes.length;

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();

        var streamOverheadBytes = CodedOutputStream.computeStringSize(
            TrafficStream.CONNECTIONID_FIELD_NUMBER,
            TEST_TRAFFIC_STREAM_ID_STRING
        ) + CodedOutputStream.computeStringSize(TrafficStream.NODEID_FIELD_NUMBER, TEST_NODE_ID_STRING);
        var spaceNeededForFlush = CodedOutputStream.computeInt32Size(
            TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER,
            packetData.length()
        );

        var bufferSizeToGetDesiredChunks = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(
            REFERENCE_TIMESTAMP,
            TrafficObservation.WRITESEGMENT_FIELD_NUMBER,
            WriteSegmentObservation.DATA_FIELD_NUMBER,
            Unpooled.wrappedBuffer(packetBytes, 0, packetBytes.length / numberOfChunks)
        ) + streamOverheadBytes + spaceNeededForFlush;

        assert CodedOutputStreamSizeUtil.computeByteBufRemainingSizeNoTag(
            Unpooled.wrappedBuffer(packetBytes, 0, packetBytes.length / numberOfChunks)
        ) == 2;

        var serializer = createSerializerWithTestHandler(outputBuffersCreated, bufferSizeToGetDesiredChunks);

        serializer.addWriteEvent(REFERENCE_TIMESTAMP, Unpooled.wrappedBuffer(packetBytes));

        var future = serializer.flushCommitAndResetStream(true);
        future.get();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.add(trafficStream.getSubStream(0));
        }

        StringBuilder reconstructedData = new StringBuilder();
        for (TrafficObservation observation : observations) {
            var stringChunk = observation.getWriteSegment().getData().toStringUtf8();
            reconstructedData.append(stringChunk);
        }
        Assertions.assertEquals(packetData, reconstructedData.toString());
        // Expect extra observation for EndOfSegmentMessage when chunked
        Assertions.assertEquals(numberOfChunks + 1, observations.size());
    }

    @Test
    public void testExceptionWhenStreamTooSmallForObservation() throws IOException, ExecutionException,
        InterruptedException {
        byte[] packetBytes = new byte[1];
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();

        var streamOverheadBytes = CodedOutputStream.computeStringSize(
            TrafficStream.CONNECTIONID_FIELD_NUMBER,
            TEST_TRAFFIC_STREAM_ID_STRING
        ) + CodedOutputStream.computeStringSize(TrafficStream.NODEID_FIELD_NUMBER, TEST_NODE_ID_STRING);
        var spaceNeededForFlush = CodedOutputStream.computeInt32Size(
            TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER,
            1
        );

        var bufferSizeWithoutSpaceForBytes = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(
            REFERENCE_TIMESTAMP,
            TrafficObservation.WRITESEGMENT_FIELD_NUMBER,
            WriteSegmentObservation.DATA_FIELD_NUMBER,
            Unpooled.buffer()
        ) + streamOverheadBytes + spaceNeededForFlush;

        var serializer = createSerializerWithTestHandler(outputBuffersCreated, bufferSizeWithoutSpaceForBytes);

        Assertions.assertThrows(IllegalStateException.class, () -> {
            serializer.addWriteEvent(REFERENCE_TIMESTAMP, Unpooled.wrappedBuffer(packetBytes));
        });

        var future = serializer.flushCommitAndResetStream(true);
        future.get();
    }

    @Test
    public void testCloseObservationAfterWriteWillFlushWhenSpaceNeeded() throws IOException, ExecutionException,
        InterruptedException {
        byte[] packetBytes = FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking small buffer that can only hold one write observation and no other observations
        var serializer = createSerializerWithTestHandler(
            outputBuffersCreated,
            getEstimatedTrafficStreamByteSize(1, packetBytes.length) - CloseObservation.newBuilder()
                .build()
                .getSerializedSize()
        );

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
    public void testEmptyPacketIsHandledForSmallCodedOutputStream() throws IOException, ExecutionException,
        InterruptedException {
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

    @ParameterizedTest
    @ValueSource(ints = { 1, 16 })
    public void testWriteIsHandledForBufferWithNioBufAllocatedLargerThanWritten(int numberOfChunks) throws IOException,
        ExecutionException, InterruptedException {
        // Use ByteBuf that returns nioBuffer with limit < capacity this can cause some edge cases to trigger depending
        // on specific CodedOutputStream apis used
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();

        var dataBytes = FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8);

        Assertions.assertTrue(dataBytes.length >= numberOfChunks);

        var streamOverheadBytes = CodedOutputStream.computeStringSize(
            TrafficStream.CONNECTIONID_FIELD_NUMBER,
            TEST_TRAFFIC_STREAM_ID_STRING
        ) + CodedOutputStream.computeStringSize(TrafficStream.NODEID_FIELD_NUMBER, TEST_NODE_ID_STRING) + 2;

        var bufferSizeToGetDesiredChunks = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(
            REFERENCE_TIMESTAMP,
            1,
            2,
            Unpooled.wrappedBuffer(dataBytes, 0, dataBytes.length / numberOfChunks)
        ) + streamOverheadBytes;

        var serializer = createSerializerWithTestHandler(outputBuffersCreated, bufferSizeToGetDesiredChunks);

        var readOnlyBuf = createReadOnlyByteBufWithNioByteBufferWithCapacityLargerThanLimit(dataBytes);

        serializer.addWriteEvent(REFERENCE_TIMESTAMP, readOnlyBuf);

        var future = serializer.flushCommitAndResetStream(true);
        future.get();

        List<TrafficObservation> observations = new ArrayList<>();
        for (ByteBuffer buffer : outputBuffersCreated) {
            var trafficStream = TrafficStream.parseFrom(buffer);
            observations.add(trafficStream.getSubStream(0));
        }

        StringBuilder reconstructedData = new StringBuilder();
        for (TrafficObservation observation : observations) {
            var stringChunk = ((numberOfChunks == 1)
                ? observation.getWrite().getData()
                : observation.getWriteSegment().getData()).toStringUtf8();
            reconstructedData.append(stringChunk);
        }
        Assertions.assertEquals(FAKE_READ_PACKET_DATA, reconstructedData.toString());
        // Expect extra observation for EndOfSegmentMessage when chunked
        var expectedObservations = numberOfChunks > 1 ? numberOfChunks + 1 : numberOfChunks;
        Assertions.assertEquals(expectedObservations, observations.size());
        Assertions.assertEquals(0, readOnlyBuf.readerIndex());
    }

    public static io.netty.buffer.ByteBuf createReadOnlyByteBufWithNioByteBufferWithCapacityLargerThanLimit(
        byte[] dataBytes
    ) {
        // Force creation of a ByteBuf that returns nioBuffer with limit < capacity.
        // This can cause some edge cases to trigger during interaction with underlying ByteBuffers
        final int spaceBetweenLimitAndCapacity = 100;
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(dataBytes.length + spaceBetweenLimitAndCapacity);
        byteBuffer.put(dataBytes);
        byteBuffer.position(dataBytes.length + spaceBetweenLimitAndCapacity);
        byteBuffer.flip();

        io.netty.buffer.ByteBuf buf = Unpooled.wrappedBuffer(byteBuffer.asReadOnlyBuffer());
        buf.writerIndex(buf.writerIndex() - spaceBetweenLimitAndCapacity);

        // Double check ByteBuf behaves as expected
        var nioBuf = buf.nioBuffer();
        Assertions.assertEquals(0, nioBuf.position());
        Assertions.assertEquals(dataBytes.length + spaceBetweenLimitAndCapacity, nioBuf.capacity());
        Assertions.assertEquals(dataBytes.length, nioBuf.limit());

        Assertions.assertEquals(dataBytes.length, buf.readableBytes());
        Assertions.assertEquals(0, buf.readerIndex());
        Assertions.assertEquals(dataBytes.length + spaceBetweenLimitAndCapacity, buf.capacity());

        return buf;
    }

    @Test
    public void testWithLimitlessCodedOutputStreamHolder() throws IOException, ExecutionException,
        InterruptedException {

        var serializer = new StreamChannelConnectionCaptureSerializer<>(
            TEST_NODE_ID_STRING,
            TEST_TRAFFIC_STREAM_ID_STRING,
            new NullStreamManager()
        );

        var bb = Unpooled.buffer(0);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        serializer.addWriteEvent(REFERENCE_TIMESTAMP, bb);
        Assertions.assertDoesNotThrow(() -> {
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
    public void testEndOfSegmentsIndicationAddedWhenChunking() throws IOException, ExecutionException,
        InterruptedException {
        var packetData = FAKE_READ_PACKET_DATA.repeat(500);
        byte[] packetBytes = packetData.getBytes(StandardCharsets.UTF_8);
        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        // Picking buffer to half size to require chunking
        var serializer = createSerializerWithTestHandler(
            outputBuffersCreated,
            getEstimatedTrafficStreamByteSize(1, packetBytes.length) / 2
        );

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
    public void testEndOfSegmentsIndicationNotAddedWhenNotChunking() throws IOException, ExecutionException,
        InterruptedException {
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
        Assertions.assertThrows(
            AssertionError.class,
            () -> new StreamChannelConnectionCaptureSerializer<>(
                "a" + tooLargeNodeId,
                realKafkaConnectionId,
                new StreamManager(getEstimatedTrafficStreamByteSize(0, 0), outputBuffersCreated)
            )
        );
    }

    @ParameterizedTest
    @CsvSource(value = {
        // Test with totalAvailableSpace >= requestedWriteableSpace
        "2,2",
        "3,2",
        "4,2",

        // Test around length where length bytes increases with totalAvailableSpace < requestedWriteableSpace
        "1,10",
        "2,10",
        "3,10",

        "127,10000000",
        "128,10000000", // 2^7
        "129,10000000",
        "130,10000000",
        "131,10000000",
        "132,10000000",

        "16381,10000000",
        "16382,10000000",
        "16383,10000000",
        "16384,10000000", // 2^14
        "16385,10000000",
        "16386,10000000",
        "16387,10000000",
        "16388,10000000",
        "16389,10000000",

        "2097150,10000000",
        "2097151,10000000",
        "2097152,10000000", // 2^21
        "2097153,10000000",
        "2097154,10000000",
        "2097155,10000000",
        "2097156,10000000",
        "2097157,10000000",
        "2097158,10000000",

        "268435455,100000000",
        "268435456,100000000", // 2^28
        "268435457,100000000",
        "268435458,100000000",
        "268435459,100000000",
        "268435460,100000000",
        "268435461,100000000",
        "268435462,100000000" })
    public void test_computeMaxLengthDelimitedFieldSizeForSpace(int totalAvailableSpace, int requestedWriteableSpace) {
        var optimalMaxWriteableSpace = optimalComputeMaxLengthDelimitedFieldSizeForSpace(
            totalAvailableSpace,
            requestedWriteableSpace
        );

        Assertions.assertTrue(
            optimalMaxWriteableSpace <= requestedWriteableSpace,
            "cannot write more bytes than requested"
        );

        var spaceLeftAfterWrite = totalAvailableSpace - CodedOutputStream.computeInt32SizeNoTag(
            optimalMaxWriteableSpace
        ) - optimalMaxWriteableSpace;
        Assertions.assertTrue(spaceLeftAfterWrite >= 0, "expected non-negative space left");
        if (optimalMaxWriteableSpace < requestedWriteableSpace) {
            Assertions.assertTrue(
                spaceLeftAfterWrite <= 1,
                "expected space left to be no more than 1 if" + "not enough space for requestedWriteableSpace"
            );
        }

        if (optimalMaxWriteableSpace < requestedWriteableSpace) {
            // If we are not writing all requestedWriteableSpace verify there is not space for one more byte
            var expectedSpaceLeftAfterWriteIfOneMoreByteWrote = totalAvailableSpace - CodedOutputStream
                .computeInt32SizeNoTag(optimalMaxWriteableSpace) - CodedOutputStream.computeInt32SizeNoTag(
                    optimalMaxWriteableSpace + 1
                ) - (optimalMaxWriteableSpace + 1);
            Assertions.assertTrue(
                expectedSpaceLeftAfterWriteIfOneMoreByteWrote < 0,
                "expected no space to write one more byte"
            );
        }

        // Test that when maxWriteableSpace != optimalMaxWriteableSpace, then
        // it is positive and equal to calculateMaxWritableSpace - 1
        var maxWriteableSpace = StreamChannelConnectionCaptureSerializer.computeMaxLengthDelimitedFieldSizeForSpace(
            totalAvailableSpace,
            requestedWriteableSpace
        );
        if (maxWriteableSpace != optimalMaxWriteableSpace) {
            Assertions.assertTrue(maxWriteableSpace > 0);
            Assertions.assertEquals(optimalMaxWriteableSpace - 1, maxWriteableSpace);
            var spaceLeftIfWritten = totalAvailableSpace - CodedOutputStream.computeInt32SizeNoTag(maxWriteableSpace)
                - maxWriteableSpace;
            if (maxWriteableSpace < requestedWriteableSpace) {
                Assertions.assertTrue(
                    spaceLeftIfWritten <= 1,
                    "expected pessimistic space left to be no more than 1 if"
                        + "not enough space for requestedWriteableSpace"
                );
            }
        }
    }

    // Optimally calculates maxWriteableSpace taking into account lengthSpace. In some cases, this may
    // yield a maxWriteableSpace with one leftover byte, however, this is still the correct max as attempting to write
    // the additional byte would yield an additional length byte and overflow
    public static int optimalComputeMaxLengthDelimitedFieldSizeForSpace(
        int totalAvailableSpace,
        int requestedWriteableSpace
    ) {
        // Overestimate the lengthFieldSpace first to then correct in instances where writing one more byte does not
        // result
        // in as large a lengthFieldSpace. In instances where we must have a leftoverByte, it will be in the
        // lengthFieldSpace
        final int maxLengthFieldSpace = CodedOutputStream.computeUInt32SizeNoTag(totalAvailableSpace);
        final int initialEstimatedMaxWriteSpace = totalAvailableSpace - maxLengthFieldSpace;
        final int lengthFieldSpaceForInitialEstimatedAndOneMoreByte = CodedOutputStream.computeUInt32SizeNoTag(
            initialEstimatedMaxWriteSpace + 1
        );

        int maxWriteBytesSpace = totalAvailableSpace - lengthFieldSpaceForInitialEstimatedAndOneMoreByte;

        return Math.min(maxWriteBytesSpace, requestedWriteableSpace);
    }

    @Test
    public void testInitializationWithRealIds() {
        final String realNodeId = "b671d2f2-577b-414e-9eb4-8bc3e89ee182";
        final String realKafkaConnectionId = "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2";

        var outputBuffersCreated = new ConcurrentLinkedQueue<ByteBuffer>();
        new StreamChannelConnectionCaptureSerializer<>(
            realNodeId,
            realKafkaConnectionId,
            new StreamManager(getEstimatedTrafficStreamByteSize(0, 0), outputBuffersCreated)
        );
    }

    private StreamChannelConnectionCaptureSerializer<Void> createSerializerWithTestHandler(
        ConcurrentLinkedQueue<ByteBuffer> outputBuffers,
        int bufferSize
    ) {
        return new StreamChannelConnectionCaptureSerializer<>(
            TEST_NODE_ID_STRING,
            TEST_TRAFFIC_STREAM_ID_STRING,
            new StreamManager(bufferSize, outputBuffers)
        );
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
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            log.atTrace().setMessage("Getting ready to flush for {}").addArgument(osh).log();
            log.atTrace()
                .setMessage("Bytes written so far... {}")
                .addArgument(() -> StandardCharsets.UTF_8.decode(osh.getByteBuffer().duplicate()))
                .log();

            return CompletableFuture.runAsync(() -> {
                try {
                    osh.getOutputStream().flush();
                    log.atTrace().setMessage("Just flushed for {}").addArgument(osh.getOutputStream()).log();
                    var bb = osh.getByteBuffer();
                    bb.position(0);
                    var bytesWritten = osh.getOutputStream().getTotalBytesWritten();
                    bb.limit(bytesWritten);
                    log.atTrace()
                        .setMessage("Adding {}")
                        .addArgument(() -> StandardCharsets.UTF_8.decode(bb.duplicate()))
                        .log();
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
                        OutputStream.nullOutputStream()
                    );

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
            public CompletableFuture<CodedOutputStreamHolder> closeStream(
                CodedOutputStreamHolder outputStreamHolder,
                int index
            ) {
                return CompletableFuture.completedFuture(null);
            }

        }
    }
}
