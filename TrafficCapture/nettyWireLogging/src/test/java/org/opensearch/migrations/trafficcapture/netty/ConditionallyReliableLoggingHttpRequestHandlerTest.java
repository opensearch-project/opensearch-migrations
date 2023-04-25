package org.opensearch.migrations.trafficcapture.netty;

import com.google.common.primitives.Bytes;
import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureOffloader;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConditionallyReliableLoggingHttpRequestHandlerTest {

    @Test
    public void testThatSinglePacketPostBlocks() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        var bigBuff = Unpooled.wrappedBuffer(fullTrafficBytes);
        writeMessageAndVerify(fullTrafficBytes, w -> {
            w.writeInbound(bigBuff);
        });
    }

    private static void writeMessageAndVerify(byte[] fullTrafficBytes, Consumer<EmbeddedChannel> channelWriter)
            throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        AtomicInteger flushCount = new AtomicInteger();
        var offloader = new StreamChannelConnectionCaptureSerializer("Test",
                () -> CodedOutputStream.newInstance(byteArrayOutputStream),
                cos -> {
                    try {
                        cos.flush();
                    } catch (IOException e) {
                        throw new RuntimeException();
                    }
                    return CompletableFuture.completedFuture(flushCount.incrementAndGet());
                });

        EmbeddedChannel channel = new EmbeddedChannel(new ConditionallyReliableLoggingHttpRequestHandler(offloader,
                x->true));
        channelWriter.accept(channel);

        var trafficStream = TrafficStream.parseFrom(byteArrayOutputStream.toByteArray());
        Assertions.assertTrue(trafficStream.getSubStreamCount() > 0 &&
                trafficStream.getSubStream(0).hasRead());
        var combinedTrafficPacketsSteam = new SequenceInputStream(Collections.enumeration(trafficStream.getSubStreamList().stream()
                .filter(to->to.hasRead())
                .map(to->new ByteArrayInputStream(to.getRead().getData().toByteArray()))
                .collect(Collectors.toList())));
        Assertions.assertArrayEquals(fullTrafficBytes, combinedTrafficPacketsSteam.readAllBytes());
        Assertions.assertEquals(1, flushCount.get());
    }

    @Test
    public void testThatTinyPacketsPostBlocks() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        writeMessageAndVerify(fullTrafficBytes, w -> {
            for (int i=0; i<fullTrafficBytes.length; ++i) {
                var singleByte = Unpooled.wrappedBuffer(fullTrafficBytes, i, 1);
                w.writeInbound(singleByte);
            }
        });
    }
}