package org.opensearch.migrations.trafficcapture.netty;

import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamAndByteBufferWrapper;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class ConditionallyReliableLoggingHttpRequestHandlerTest {

    @AllArgsConstructor
    static class StreamManager extends OrderedStreamLifecyleManager {
        AtomicReference<ByteBuffer> byteBufferAtomicReference;
        AtomicInteger flushCount = new AtomicInteger();

        @Override
        public CodedOutputStreamAndByteBufferWrapper createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(1024*1024);
        }

        @Override
        public CompletableFuture<Object>
        kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
                throw new RuntimeException("Unknown outputStreamHolder sent back to StreamManager: " +
                        outputStreamHolder);
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            CodedOutputStream cos = osh.getOutputStream();
            try {
                cos.flush();
                byteBufferAtomicReference.set(osh.getByteBuffer().flip().asReadOnlyBuffer());
                log.error("byteBufferAtomicReference.get="+byteBufferAtomicReference.get());
            } catch (IOException e) {
                throw new RuntimeException();
            }
            return CompletableFuture.completedFuture(flushCount.incrementAndGet());
        }
    }


    private static void writeMessageAndVerify(byte[] fullTrafficBytes, Consumer<EmbeddedChannel> channelWriter)
            throws IOException {
        AtomicReference<ByteBuffer> outputByteBuffer = new AtomicReference<>();
        AtomicInteger flushCount = new AtomicInteger();
        var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection",
                new StreamManager(outputByteBuffer, flushCount));

        EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpRequestHandler(offloader, x->true)); // true: block every request
        channelWriter.accept(channel);

        // we wrote the correct data to the downstream handler/channel
        var outputData = new SequenceInputStream(Collections.enumeration(channel.inboundMessages().stream()
                .map(m->new ByteArrayInputStream(consumeIntoArray((ByteBuf)m)))
                .collect(Collectors.toList())))
                .readAllBytes();
        Assertions.assertArrayEquals(fullTrafficBytes, outputData);

        Assertions.assertNotNull(outputByteBuffer,
                "This would be null if the handler didn't block until the output was written");
        // we wrote the correct data to the offloaded stream
        var trafficStream = TrafficStream.parseFrom(outputByteBuffer.get());
        Assertions.assertTrue(trafficStream.getSubStreamCount() > 0 &&
                trafficStream.getSubStream(0).hasRead());
        var combinedTrafficPacketsSteam =
                new SequenceInputStream(Collections.enumeration(trafficStream.getSubStreamList().stream()
                .filter(to->to.hasRead())
                .map(to->new ByteArrayInputStream(to.getRead().getData().toByteArray()))
                .collect(Collectors.toList())));
        Assertions.assertArrayEquals(fullTrafficBytes, combinedTrafficPacketsSteam.readAllBytes());
        Assertions.assertEquals(1, flushCount.get());
    }

    private static byte[] consumeIntoArray(ByteBuf m) {
        var bArr = new byte[m.readableBytes()];
        m.readBytes(bArr);
        m.release();
        return bArr;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testThatAPostInASinglePacketBlocksFutureActivity(boolean usePool) throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        var bb = TestUtilities.getByteBuf(fullTrafficBytes, usePool);
        writeMessageAndVerify(fullTrafficBytes, w -> {
            w.writeInbound(bb);
        });
        log.info("buf.refCnt="+bb.refCnt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testThatAPostInTinyPacketsBlocksFutureActivity(boolean usePool) throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        writeMessageAndVerify(fullTrafficBytes, w -> {
            for (int i=0; i<fullTrafficBytes.length; ++i) {
                var singleByte = TestUtilities.getByteBuf(Arrays.copyOfRange(fullTrafficBytes, i, i+1), usePool);
                w.writeInbound(singleByte);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WrapWithNettyLeakDetection(repetitions = 16)
    public void testThatAPostInTinyPacketsBlocksFutureActivity_withLeakDetection(boolean usePool) throws Exception {
        testThatAPostInTinyPacketsBlocksFutureActivity(usePool);
        //MyResourceLeakDetector.dumpHeap("nettyWireLogging_"+COUNT+"_"+ Instant.now() +".hprof", true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WrapWithNettyLeakDetection(repetitions = 32)
    public void testThatAPostInASinglePacketBlocksFutureActivity_withLeakDetection(boolean usePool) throws Exception {
        testThatAPostInASinglePacketBlocksFutureActivity(usePool);
        //MyResourceLeakDetector.dumpHeap("nettyWireLogging_"+COUNT+"_"+ Instant.now() +".hprof", true);
    }

}
