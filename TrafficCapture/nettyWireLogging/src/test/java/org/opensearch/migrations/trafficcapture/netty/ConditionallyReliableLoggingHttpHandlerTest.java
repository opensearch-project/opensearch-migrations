package org.opensearch.migrations.trafficcapture.netty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection
public class ConditionallyReliableLoggingHttpHandlerTest {

    private static void writeMessageAndVerify(
        byte[] fullTrafficBytes,
        Consumer<EmbeddedChannel> channelWriter,
        boolean checkInstrumentation
    ) throws IOException {
        try (var rootContext = new TestRootContext(checkInstrumentation, checkInstrumentation)) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "c",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    x -> true
                )
            ); // true: block every request
            channelWriter.accept(channel);

            // we wrote the correct data to the downstream handler/channel
            var outputDataStream = new SequenceInputStream(
                Collections.enumeration(
                    channel.inboundMessages()
                        .stream()
                        .map(m -> new ByteBufInputStream((ByteBuf) m, false))
                        .collect(Collectors.toList())
                )
            );
            var outputData = outputDataStream.readAllBytes();
            outputDataStream.close();
            Assertions.assertArrayEquals(fullTrafficBytes, outputData);

            Assertions.assertNotNull(
                streamManager.byteBufferAtomicReference.get(),
                "This would be null if the handler didn't block until the output was written"
            );
            // we wrote the correct data to the offloaded stream
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
            Assertions.assertEquals(1, streamManager.flushCount.get());

            if (checkInstrumentation) {
                Assertions.assertTrue(!rootContext.instrumentationBundle.getFinishedSpans().isEmpty());
                Assertions.assertTrue(!rootContext.instrumentationBundle.getFinishedMetrics().isEmpty());
            }

            channel.finishAndReleaseAll();
            channel.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testThatAPostInASinglePacketBlocksFutureActivity(boolean usePool) throws IOException {
        testThatAPostInASinglePacketBlocksFutureActivity(usePool, true);
    }

    public void testThatAPostInASinglePacketBlocksFutureActivity(boolean usePool, boolean checkInstrumentation)
        throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        var bb = TestUtilities.getByteBuf(fullTrafficBytes, usePool);
        writeMessageAndVerify(fullTrafficBytes, w -> w.writeInbound(bb), checkInstrumentation);
        log.info("buf.refCnt=" + bb.refCnt());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testThatAPostInTinyPacketsBlocksFutureActivity(boolean usePool) throws IOException {
        testThatAPostInTinyPacketsBlocksFutureActivity(usePool, true);
    }

    public void testThatAPostInTinyPacketsBlocksFutureActivity(boolean usePool, boolean checkInstrumentation)
        throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
        writeMessageAndVerify(
            fullTrafficBytes,
            getSingleByteAtATimeWriter(usePool, fullTrafficBytes),
            checkInstrumentation
        );
    }

    private static Consumer<EmbeddedChannel> getSingleByteAtATimeWriter(boolean usePool, byte[] fullTrafficBytes) {
        return w -> {
            for (int i = 0; i < fullTrafficBytes.length; ++i) {
                var singleByte = TestUtilities.getByteBuf(Arrays.copyOfRange(fullTrafficBytes, i, i + 1), usePool);
                w.writeInbound(singleByte);
            }
        };
    }

    // This test doesn't work yet, but this is an optimization. Getting connections with only a
    // close observation is already a common occurrence. This is nice to have, so it's good to
    // keep this warm and ready, but we don't need the feature for correctness.
    @Disabled("This is for an optimization that isn't functional yet")
    @Test
    @ValueSource(booleans = { false, true })
    public void testThatSuppressedCaptureWorks() throws Exception {
        try (var rootInstrumenter = new TestRootContext()) {
            var streamMgr = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection", streamMgr);

            var headerCapturePredicate = new HeaderValueFilteringCapturePredicate(Map.of("user-Agent", "uploader"));
            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootInstrumenter,
                    "n",
                    "c",
                    ctx -> offloader,
                    headerCapturePredicate,
                    x -> true
                )
            );
            getWriter(false, true, SimpleRequests.HEALTH_CHECK.getBytes(StandardCharsets.UTF_8)).accept(channel);
            channel.finishAndReleaseAll();
            channel.close();
            var requestBytes = SimpleRequests.HEALTH_CHECK.getBytes(StandardCharsets.UTF_8);

            Assertions.assertEquals(0, streamMgr.flushCount.get());
            // we wrote the correct data to the downstream handler/channel
            var outputData = new SequenceInputStream(
                Collections.enumeration(
                    channel.inboundMessages()
                        .stream()
                        .map(m -> new ByteBufInputStream((ByteBuf) m, true))
                        .collect(Collectors.toList())
                )
            ).readAllBytes();
            log.info("outputdata = " + new String(outputData, StandardCharsets.UTF_8));
            Assertions.assertArrayEquals(requestBytes, outputData);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void testThatHealthCheckCaptureCanBeSuppressed(boolean singleBytes) throws Exception {
        try (var rootInstrumenter = new TestRootContext()) {
            var streamMgr = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection", streamMgr);

            var headerCapturePredicate = new HeaderValueFilteringCapturePredicate(Map.of("user-Agent", ".*uploader.*"));
            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootInstrumenter,
                    "n",
                    "c",
                    ctx -> offloader,
                    headerCapturePredicate,
                    x -> false
                )
            );
            getWriter(singleBytes, true, SimpleRequests.HEALTH_CHECK.getBytes(StandardCharsets.UTF_8)).accept(channel);
            channel.writeOutbound(Unpooled.wrappedBuffer("response1".getBytes(StandardCharsets.UTF_8)));
            getWriter(singleBytes, true, SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8)).accept(channel);
            var bytesForResponsePreserved = "response2".getBytes(StandardCharsets.UTF_8);
            channel.writeOutbound(Unpooled.wrappedBuffer(bytesForResponsePreserved));
            channel.close();
            var requestBytes = (SimpleRequests.HEALTH_CHECK + SimpleRequests.SMALL_POST).getBytes(
                StandardCharsets.UTF_8
            );

            // we wrote the correct data to the downstream handler/channel
            var consumedData = new SequenceInputStream(
                Collections.enumeration(
                    channel.inboundMessages()
                        .stream()
                        .map(m -> new ByteBufInputStream((ByteBuf) m, false))
                        .collect(Collectors.toList())
                )
            ).readAllBytes();
            log.info("captureddata = " + new String(consumedData, StandardCharsets.UTF_8));
            Assertions.assertArrayEquals(requestBytes, consumedData);

            Assertions.assertNotNull(
                streamMgr.byteBufferAtomicReference,
                "This would be null if the handler didn't block until the output was written"
            );
            // we wrote the correct data to the offloaded stream
            var trafficStream = TrafficStream.parseFrom(streamMgr.byteBufferAtomicReference.get());
            Assertions.assertTrue(trafficStream.getSubStreamCount() > 0 && trafficStream.getSubStream(0).hasRead());
            Assertions.assertEquals(1, streamMgr.flushCount.get());
            var observations = trafficStream.getSubStreamList();
            {
                var readObservationStreamToUse = singleBytes
                    ? skipReadsBeforeDrop(observations)
                    : observations.stream();
                var combinedTrafficPacketsSteam = new SequenceInputStream(
                    Collections.enumeration(
                        readObservationStreamToUse.filter(to -> to.hasRead())
                            .map(to -> new ByteArrayInputStream(to.getRead().getData().toByteArray()))
                            .collect(Collectors.toList())
                    )
                );
                var reconstitutedTrafficStreamReads = combinedTrafficPacketsSteam.readAllBytes();
                Assertions.assertArrayEquals(
                    SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8),
                    reconstitutedTrafficStreamReads
                );
            }

            // check that we only got one response
            {
                var combinedTrafficPacketsSteam = new SequenceInputStream(
                    Collections.enumeration(
                        observations.stream()
                            .filter(to -> to.hasWrite())
                            .map(to -> new ByteArrayInputStream(to.getWrite().getData().toByteArray()))
                            .collect(Collectors.toList())
                    )
                );
                var reconstitutedTrafficStreamWrites = combinedTrafficPacketsSteam.readAllBytes();
                log.info(
                    "reconstitutedTrafficStreamWrites="
                        + new String(reconstitutedTrafficStreamWrites, StandardCharsets.UTF_8)
                );
                Assertions.assertArrayEquals(bytesForResponsePreserved, reconstitutedTrafficStreamWrites);
            }

            channel.finishAndReleaseAll();
        }
    }

    private static Stream<TrafficObservation> skipReadsBeforeDrop(List<TrafficObservation> observations) {
        var sawRequestDropped = new AtomicBoolean(false);
        return observations.stream().dropWhile(o -> {
            var wasDrop = o.hasRequestDropped();
            sawRequestDropped.compareAndSet(false, wasDrop);
            return !sawRequestDropped.get() || wasDrop;
        });
    }

    private Consumer<EmbeddedChannel> getWriter(boolean singleBytes, boolean usePool, byte[] bytes) {
        if (singleBytes) {
            return getSingleByteAtATimeWriter(usePool, bytes);
        } else {
            return w -> w.writeInbound(Unpooled.wrappedBuffer(bytes));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testThatAPostInTinyPacketsBlocksFutureActivity_withLeakDetection(boolean usePool) throws Exception {
        testThatAPostInTinyPacketsBlocksFutureActivity(usePool, false);
        // MyResourceLeakDetector.dumpHeap("nettyWireLogging_"+COUNT+"_"+ Instant.now() +".hprof", true);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    @WrapWithNettyLeakDetection(repetitions = 32)
    public void testThatAPostInASinglePacketBlocksFutureActivity_withLeakDetection(boolean usePool) throws Exception {
        testThatAPostInASinglePacketBlocksFutureActivity(usePool, false);
        // MyResourceLeakDetector.dumpHeap("nettyWireLogging_"+COUNT+"_"+ Instant.now() +".hprof", true);
    }

}
