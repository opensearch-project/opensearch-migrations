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
    public void testThatReadCanBeDeserialized() throws IOException, ExecutionException, InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());
        // these are only here as a debugging aid
        var groundTruth = makeEmptyTrafficStream(referenceTimestamp);
        System.err.println("groundTruth: "+groundTruth);
        // Pasting this into `base64 -d | protoc --decode_raw` will also show the structure
        var groundTruthBytes = groundTruth.toByteArray();
        System.err.println("groundTruth Bytes: "+Base64.getEncoder().encodeToString(groundTruthBytes));

        List<ByteBuffer> outputBuffersCreated = new ArrayList<>();
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteBuffersMap = new WeakHashMap<>();
        var serializer = new StreamChannelConnectionCaptureSerializer(TEST_TRAFFIC_STREAM_ID_STRING,
                () -> {
                    ByteBuffer bytes = ByteBuffer.allocate(1024*1024);
                    var rval = CodedOutputStream.newInstance(bytes);
                    codedStreamToByteBuffersMap.put(rval, bytes);
                    return rval;
                },
                offloaderInput -> CompletableFuture.runAsync(() -> {
                    try {
                        CodedOutputStream cos = offloaderInput.getCodedOutputStream();
                        cos.flush();
                        var bb = codedStreamToByteBuffersMap.get(cos);
                        bb.position(0);
                        var bytesWritten = cos.getTotalBytesWritten();
                        bb.limit(bytesWritten);
                        outputBuffersCreated.add(bb);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
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
}