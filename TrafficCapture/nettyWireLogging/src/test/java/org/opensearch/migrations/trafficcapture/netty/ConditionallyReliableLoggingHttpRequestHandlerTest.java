package org.opensearch.migrations.trafficcapture.netty;

import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.migrations.testutils.CountingNettyResourceLeakDetector;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
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
class ConditionallyReliableLoggingHttpRequestHandlerTest {

    @BeforeAll
    public static void setup() {
        ResourceLeakDetectorFactory.setResourceLeakDetectorFactory(new CountingNettyResourceLeakDetector.MyResourceLeakDetectorFactory());
        CountingNettyResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    private static void writeMessageAndVerify(byte[] fullTrafficBytes, Consumer<EmbeddedChannel> channelWriter)
            throws IOException {
        AtomicReference<ByteBuffer> outputByteBuffer = new AtomicReference<>();
        byte[] scratchBytes = new byte[1024*1024];
        AtomicInteger flushCount = new AtomicInteger();
        var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection",
                () -> CodedOutputStream.newInstance(scratchBytes),
                captureSerializerResult -> {
                    CodedOutputStream cos = captureSerializerResult.getCodedOutputStream();
                    outputByteBuffer.set(ByteBuffer.wrap(scratchBytes, 0, cos.getTotalBytesWritten()));
                    try {
                        cos.flush();
                    } catch (IOException e) {
                        throw new RuntimeException();
                    }
                    return CompletableFuture.completedFuture(flushCount.incrementAndGet());
                });

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

    private ByteBuf getByteBuf(byte[] src, boolean usePool) {
        var unpooled = Unpooled.wrappedBuffer(src);
        if (usePool) {
            var pooled = ByteBufAllocator.DEFAULT.buffer(src.length);
            pooled.writeBytes(unpooled);
            unpooled.release();
            return pooled;
        } else {
            return unpooled;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testThatAPostInASinglePacketBlocksFutureActivity(boolean usePool) throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        var bb = getByteBuf(fullTrafficBytes, usePool);
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
                var singleByte = getByteBuf(Arrays.copyOfRange(fullTrafficBytes, i, i+1), usePool);
                w.writeInbound(singleByte);
            }
        });
    }

    @Test
    public void testThatAPostInTinyPacketsBlocksFutureActivity_withLeakDetection() throws Exception {
        CountingNettyResourceLeakDetector.activate();

        var COUNT = 32;
        for (int i=0; i<COUNT; ++i) {
            testThatAPostInTinyPacketsBlocksFutureActivity((i%2)==0);
            System.gc();
            System.runFinalization();
        }
        Assertions.assertEquals(0, CountingNettyResourceLeakDetector.getNumLeaks());
        //MyResourceLeakDetector.dumpHeap("nettyWireLogging_"+COUNT+"_"+ Instant.now() +".hprof", true);
    }

    @Test
    public void testThatAPostInASinglePacketBlocksFutureActivity_withLeakDetection() throws Exception {
        CountingNettyResourceLeakDetector.activate();

        var COUNT = 64;
        for (int i=0; i<COUNT; ++i) {
            testThatAPostInASinglePacketBlocksFutureActivity((i%2)==0);
            System.gc();
            System.runFinalization();
            Assertions.assertEquals(0, CountingNettyResourceLeakDetector.getNumLeaks());
        }
        //MyResourceLeakDetector.dumpHeap("nettyWireLogging_"+COUNT+"_"+ Instant.now() +".hprof", true);
    }

}
