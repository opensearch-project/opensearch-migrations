package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

class StreamChannelConnectionCaptureSerializerTest {
    private final static String FAKE_READ_PACKET_DATA = "";//I am a fake packet to read2";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "Test";

    private static TrafficStream makeEmptyTrafficStreamBytes(Instant t) {
        return TrafficStream.newBuilder().setId(TEST_TRAFFIC_STREAM_ID_STRING)
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
                        .setException(Exception.newBuilder()
                                .setMessage(FAKE_READ_PACKET_DATA)
                                .build())
                        .build())
                .build();
    }

    @Test
    public void testThatReadCanBeDeserialized() throws IOException {
        var referenceTimestamp = Instant.now(Clock.systemUTC());
        // these are only here as a debugging aid
        var groundTruth = makeEmptyTrafficStreamBytes(referenceTimestamp);
        var groundTruthBytes = groundTruth.toByteArray();
        String tmp = Base64.getEncoder().encodeToString(groundTruthBytes);
        // base64 -d | protoc --decode_raw
        //CgRUZXN0EiQKBAgIEAFCHAoaSSBhbSBhIGZha2UgcGFja2V0IHRvIHJlYWQgAQ==
        //1: "Test"
        //2 {
        //  1 {
        //    1: 8
        //    2: 1
        //  }
        //  8 {
        //    1: "I am a fake packet to read"
        //  }
        //}
        //4: 1


        var startingTimeMillis = System.currentTimeMillis();
        List<ByteBuffer> outputBuffersCreated = new ArrayList<>();
        var serializer = new StreamChannelConnectionCaptureSerializer(TEST_TRAFFIC_STREAM_ID_STRING,
                () -> {
                    ByteBuffer bytes = ByteBuffer.allocate(1024*1024);
                    outputBuffersCreated.add(bytes);
                    return CodedOutputStream.newInstance(bytes);
                },
                cos-> {
                    try {
                        cos.flush();
                        var bb = outputBuffersCreated.get(0);
                        bb.position(0);
                        bb.limit(cos.getTotalBytesWritten());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        var bb = Unpooled.wrappedBuffer(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8));
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addExceptionCaughtEvent(referenceTimestamp, new RuntimeException(FAKE_READ_PACKET_DATA));
        var endingTimeMillis = System.currentTimeMillis();
        serializer.close();
        bb.release();
        Assertions.assertEquals(1, outputBuffersCreated.size());
        var onlyBuffer = outputBuffersCreated.get(0);
        var reconstitutedTrafficStream = TrafficStream.parseFrom(onlyBuffer);
        Assertions.assertEquals(TEST_TRAFFIC_STREAM_ID_STRING, reconstitutedTrafficStream.getId());
        Assertions.assertEquals(2, reconstitutedTrafficStream.getSubStreamCount());
        Assertions.assertEquals(1, reconstitutedTrafficStream.getNumberOfThisLastChunk());
        Assertions.assertFalse(reconstitutedTrafficStream.hasNumber());
        {
            TrafficObservation observation = reconstitutedTrafficStream.getSubStream(0);
            Assertions.assertTrue(observation.hasTs());
            var ts = observation.getTs();
            Assertions.assertEquals(referenceTimestamp.getEpochSecond(), ts.getSeconds());
            Assertions.assertEquals(referenceTimestamp.getNano(), ts.getNanos());
            Assertions.assertTrue(observation.hasRead());
            Assertions.assertFalse(observation.hasException());
            Assertions.assertEquals(FAKE_READ_PACKET_DATA, observation.getRead().getData().toStringUtf8());
        }
        {
            TrafficObservation observation = reconstitutedTrafficStream.getSubStream(1);
            Assertions.assertTrue(observation.hasTs());
            var ts = observation.getTs();
            Assertions.assertEquals(referenceTimestamp.getEpochSecond(), ts.getSeconds());
            Assertions.assertEquals(referenceTimestamp.getNano(), ts.getNanos());
            Assertions.assertFalse(observation.hasRead());
            Assertions.assertTrue(observation.hasException());
            Assertions.assertEquals(FAKE_READ_PACKET_DATA, observation.getException().getMessage());
        }
        Assertions.assertEquals(groundTruth, reconstitutedTrafficStream);
    }
}