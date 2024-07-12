package org.opensearch.migrations.trafficcapture.netty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RootWireLoggingContextTest {

    private static void writeMessageAndVerifyTraces(
        byte[] fullTrafficBytes,
        boolean shouldBlock,
        boolean shouldCloseChannel,
        Set<String> expectedTraces
    ) throws IOException {
        try (var rootContext = new TestRootContext(true, true)) {
            Consumer<EmbeddedChannel> channelWriter = w -> w.writeInbound(
                TestUtilities.getByteBuf(fullTrafficBytes, false)
            );
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "c",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    x -> shouldBlock
                )
            );
            channelWriter.accept(channel);

            // we wrote the correct data to the downstream handler/channel
            var outputData = new SequenceInputStream(
                Collections.enumeration(
                    channel.inboundMessages()
                        .stream()
                        .map(m -> new ByteBufInputStream((ByteBuf) m, true))
                        .collect(Collectors.toList())
                )
            ).readAllBytes();
            Assertions.assertArrayEquals(fullTrafficBytes, outputData);

            // For a non-blocking write, we need to force it to flush
            if (!shouldBlock) {
                // We should not have flushed at this point
                Assertions.assertEquals(0, streamManager.flushCount.get());
                if (!shouldCloseChannel) {
                    // Force a manual flush without closing the channel
                    offloader.flushCommitAndResetStream(true).join();
                }
            }
            if (shouldCloseChannel) {
                // Fully close the channel, which should prompt a flush
                channel.close().awaitUninterruptibly();
            }

            Assertions.assertNotNull(
                streamManager.byteBufferAtomicReference.get(),
                "This would be null if the handler didn't block until the output was written"
            );
            Assertions.assertEquals(1, streamManager.flushCount.get());

            var trafficStream = TrafficStream.parseFrom(streamManager.byteBufferAtomicReference.get());
            Assertions.assertTrue(trafficStream.getSubStreamCount() > 0 && trafficStream.getSubStream(0).hasRead());
            var combinedTrafficPacketsStream = new SequenceInputStream(
                Collections.enumeration(
                    trafficStream.getSubStreamList()
                        .stream()
                        .filter(TrafficObservation::hasRead)
                        .map(to -> new ByteArrayInputStream(to.getRead().getData().toByteArray()))
                        .collect(Collectors.toList())
                )
            );
            Assertions.assertArrayEquals(fullTrafficBytes, combinedTrafficPacketsStream.readAllBytes());

            var finishedSpans = rootContext.instrumentationBundle.getFinishedSpans();
            Assertions.assertTrue(!finishedSpans.isEmpty());
            Assertions.assertTrue(!rootContext.instrumentationBundle.getFinishedMetrics().isEmpty());

            Assertions.assertEquals(
                expectedTraces.stream().sorted().collect(Collectors.joining("\n")),
                finishedSpans.stream().map(SpanData::getName).sorted().collect(Collectors.joining("\n"))
            );
        }
    }

    @Test
    public void testThatAGetProducesGatheringRequestTrace_WithoutClosingChannel() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.HEALTH_CHECK.getBytes(StandardCharsets.UTF_8);
        writeMessageAndVerifyTraces(fullTrafficBytes, false, false, Set.of("gatheringRequest"));
    }

    @Test
    public void testThatAGetProducesGatheringRequestTrace_WithClosingChannel() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.HEALTH_CHECK.getBytes(StandardCharsets.UTF_8);
        writeMessageAndVerifyTraces(
            fullTrafficBytes,
            false,
            true,
            Set.of("captureConnection", "gatheringRequest", "waitingForResponse")
        );
    }

    @Test
    public void testThatAPostProducesGatheringRequestTrace_WithoutClosingChannel() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        writeMessageAndVerifyTraces(fullTrafficBytes, true, false, Set.of("gatheringRequest", "blocked"));
    }

}
