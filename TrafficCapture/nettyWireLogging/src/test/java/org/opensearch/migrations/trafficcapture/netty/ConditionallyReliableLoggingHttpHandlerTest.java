package org.opensearch.migrations.trafficcapture.netty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Slf4j
@WrapWithNettyLeakDetection
public class ConditionallyReliableLoggingHttpHandlerTest {

    private static CaptureProcessState failOpenCaptureState() {
        return new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN);
    }

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
                    x -> true,
                    failOpenCaptureState()
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

    @Test
    void requestAssemblyDurationDoesNotLimitIdleConnectionLifetime() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection", streamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    Duration.ofMillis(1),
                    failOpenCaptureState()
                )
            );

            channel.advanceTimeBy(5, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();

            Assertions.assertTrue(channel.isOpen());
            Assertions.assertEquals(0, streamManager.flushCount.get());

            channel.close();
            channel.runPendingTasks();
            Assertions.assertEquals(1, streamManager.flushCount.get());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void requestAssemblyDurationStartsWithTheFirstByteAndIsNotResetByProgress() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var offloader = new DelayedFinalAcknowledgementOffloader();
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    Duration.ofMillis(10),
                    failOpenCaptureState()
                )
            );

            channel.writeInbound(Unpooled.copiedBuffer(
                "POST / HTTP/1.1\r\nContent-Length: 100\r\n\r\nx",
                StandardCharsets.US_ASCII
            ));
            channel.advanceTimeBy(6, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            Assertions.assertTrue(channel.isOpen());

            channel.writeInbound(Unpooled.copiedBuffer("y", StandardCharsets.US_ASCII));
            channel.advanceTimeBy(5, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, offloader.closeObservations.get());
            Assertions.assertFalse(offloader.finalAcknowledgement.isDone());

            offloader.finalAcknowledgement.complete(null);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void completedRequestCancelsItsAssemblyDeadlineAndKeepAliveConnectionRemainsValid() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection", streamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    Duration.ofMillis(1),
                    failOpenCaptureState()
                )
            );

            channel.writeInbound(Unpooled.copiedBuffer(
                "POST / HTTP/1.1\r\nContent-Length: 1\r\n\r\nx",
                StandardCharsets.US_ASCII
            ));
            channel.advanceTimeBy(5, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();

            Assertions.assertTrue(channel.isOpen());
            channel.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aggregateHeaderByteLimitStopsForwardingAndUsesNormalTerminalCapture() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection", streamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    new IncompleteRequestLimits(Duration.ofMinutes(1), 32, 1024),
                    failOpenCaptureState()
                )
            );

            channel.writeInbound(Unpooled.copiedBuffer(
                "GET / HTTP/1.1\r\nX-Large: 1234567890123456789012345678901234567890\r\n\r\n",
                StandardCharsets.US_ASCII
            ));
            channel.runPendingTasks();

            assertBoundViolationClosesWithCapturedReadThenOneTerminalClose(channel, streamManager);
        }
    }

    @Test
    void aggregateTotalRequestByteLimitIncludesBodyAndStopsForwarding() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "connection", streamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    new IncompleteRequestLimits(Duration.ofMinutes(1), 128, 150),
                    failOpenCaptureState()
                )
            );
            var request = (
                "POST / HTTP/1.1\r\nContent-Length: 200\r\n\r\n"
                    + "x".repeat(200)
            ).getBytes(StandardCharsets.US_ASCII);

            channel.writeInbound(Unpooled.wrappedBuffer(request));
            channel.runPendingTasks();

            assertBoundViolationClosesWithCapturedReadThenOneTerminalClose(channel, streamManager);
        }
    }

    private static void assertBoundViolationClosesWithCapturedReadThenOneTerminalClose(
        EmbeddedChannel channel,
        TestStreamManager streamManager
    ) throws Exception {
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertTrue(channel.inboundMessages().isEmpty());
        Assertions.assertEquals(1, streamManager.flushCount.get());

        var finalStream = TrafficStream.parseFrom(streamManager.byteBufferAtomicReference.get());
        var observations = finalStream.getSubStreamList();
        Assertions.assertTrue(observations.stream().anyMatch(TrafficObservation::hasRead));
        Assertions.assertEquals(1, observations.stream().filter(TrafficObservation::hasClose).count());
        Assertions.assertTrue(observations.get(observations.size() - 1).hasClose());

        channel.close();
        channel.runPendingTasks();
        Assertions.assertEquals(1, streamManager.flushCount.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void exceptionObservationIsDiagnosticAndChannelCloseEmitsOneTerminalObservation() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader =
                new StreamChannelConnectionCaptureSerializer<>("Test", "connection", streamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    failOpenCaptureState()
                ),
                new ExceptionConsumingHandler()
            );

            channel.pipeline().fireExceptionCaught(new IllegalStateException("unrecoverable"));
            channel.runPendingTasks();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, streamManager.flushCount.get());
            var finalStream = TrafficStream.parseFrom(streamManager.byteBufferAtomicReference.get());
            var observations = finalStream.getSubStreamList();
            Assertions.assertEquals(
                1,
                observations.stream().filter(TrafficObservation::hasConnectionException).count()
            );
            Assertions.assertEquals(1, observations.stream().filter(TrafficObservation::hasClose).count());
            Assertions.assertTrue(observations.get(observations.size() - 1).hasClose());

            channel.close();
            channel.runPendingTasks();
            Assertions.assertEquals(1, streamManager.flushCount.get());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void diagnosticCaptureFailureStillClosesTheChannelExactlyOnce() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var offloader = new DiagnosticFailureOffloader();
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    failOpenCaptureState()
                ),
                new ExceptionConsumingHandler()
            );

            channel.pipeline().fireExceptionCaught(new IllegalStateException("unrecoverable"));
            channel.runPendingTasks();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, offloader.diagnosticAttempts.get());
            Assertions.assertEquals(1, offloader.closeObservations.get());
            Assertions.assertEquals(1, offloader.finalFlushes.get());

            channel.close();
            channel.runPendingTasks();
            Assertions.assertEquals(1, offloader.closeObservations.get());
            Assertions.assertEquals(1, offloader.finalFlushes.get());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void exceptionWithoutAMessageStillProducesADiagnosticObservation() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader =
                new StreamChannelConnectionCaptureSerializer<>("Test", "connection", streamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    failOpenCaptureState()
                ),
                new ExceptionConsumingHandler()
            );

            channel.pipeline().fireExceptionCaught(new IllegalStateException());
            channel.runPendingTasks();

            var finalStream = TrafficStream.parseFrom(streamManager.byteBufferAtomicReference.get());
            var diagnostic = finalStream.getSubStreamList()
                .stream()
                .filter(TrafficObservation::hasConnectionException)
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(
                IllegalStateException.class.getName(),
                diagnostic.getConnectionException().getMessage()
            );
            Assertions.assertTrue(
                finalStream.getSubStream(finalStream.getSubStreamCount() - 1).hasClose()
            );
            channel.finishAndReleaseAll();
        }
    }

    private static class ExceptionConsumingHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // The test inspects capture and channel lifecycle directly.
        }
    }

    private static class DiagnosticFailureOffloader implements IChannelConnectionCaptureSerializer<Void> {
        private final AtomicInteger diagnosticAttempts = new AtomicInteger();
        private final AtomicInteger closeObservations = new AtomicInteger();
        private final AtomicInteger finalFlushes = new AtomicInteger();

        @Override
        public void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException {
            diagnosticAttempts.incrementAndGet();
            throw new IOException("diagnostic capture failed");
        }

        @Override
        public void addCloseEvent(Instant timestamp) {
            closeObservations.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> flushCommitAndResetStream(boolean isFinal) {
            if (isFinal) {
                finalFlushes.incrementAndGet();
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class DelayedFinalAcknowledgementOffloader
        implements IChannelConnectionCaptureSerializer<Void> {
        private final AtomicInteger closeObservations = new AtomicInteger();
        private final CompletableFuture<Void> finalAcknowledgement = new CompletableFuture<>();

        @Override
        public void addCloseEvent(Instant timestamp) {
            closeObservations.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> flushCommitAndResetStream(boolean isFinal) {
            return isFinal ? finalAcknowledgement : CompletableFuture.completedFuture(null);
        }
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


            var headerCapturePredicate = HeaderValueFilteringCapturePredicate.builder()
                .suppressCaptureHeaderPairs(Map.of("user-Agent", "uploader")).build();
            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootInstrumenter,
                    "n",
                    "c",
                    ctx -> offloader,
                    headerCapturePredicate,
                    x -> true,
                    failOpenCaptureState()
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

            var headerCapturePredicate = HeaderValueFilteringCapturePredicate.builder()
                .suppressCaptureHeaderPairs(Map.of("user-Agent", ".*uploader.*")).build();
            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootInstrumenter,
                    "n",
                    "c",
                    ctx -> offloader,
                    headerCapturePredicate,
                    x -> false,
                    failOpenCaptureState()
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

    @Test
    @WrapWithNettyLeakDetection(repetitions = 32)
    public void failOpenTransitionsTheWholeConnectionToPassThrough() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);

        try (var rootContext = new TestRootContext()) {
            var failingStreamManager = new FailingStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "c", failingStreamManager);
            var captureProcessState = new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "c",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    x -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            channel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            channel.runPendingTasks();

            Assertions.assertTrue(captureProcessState.isPassThrough());
            Assertions.assertTrue(channel.isOpen());
            Assertions.assertEquals(1, failingStreamManager.flushCount.get());

            channel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            channel.runPendingTasks();

            var forwardedBytes = channel.inboundMessages().stream()
                .mapToInt(message -> ((ByteBuf) message).readableBytes())
                .sum();
            Assertions.assertEquals(2 * fullTrafficBytes.length, forwardedBytes);
            Assertions.assertEquals(
                1,
                failingStreamManager.flushCount.get(),
                "No later request may resume capture after entering pass-through"
            );

            var secondConnectionManager = new TestStreamManager();
            var secondConnectionOffloader =
                new StreamChannelConnectionCaptureSerializer("Test", "second", secondConnectionManager);
            var secondChannel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "second",
                    ctx -> secondConnectionOffloader,
                    new RequestCapturePredicate(),
                    x -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );
            secondChannel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            secondChannel.runPendingTasks();

            var secondForwardedMessage = (ByteBuf) secondChannel.readInbound();
            try {
                Assertions.assertEquals(fullTrafficBytes.length, secondForwardedMessage.readableBytes());
            } finally {
                secondForwardedMessage.release();
            }
            Assertions.assertEquals(
                0,
                secondConnectionManager.flushCount.get(),
                "A new connection must not resume capture after the process enters pass-through"
            );
            secondChannel.finishAndReleaseAll();

            channel.finishAndReleaseAll();
            Assertions.assertEquals(
                1,
                failingStreamManager.flushCount.get(),
                "Pass-through teardown must not attempt terminal capture"
            );
        }
    }

    @Test
    void maximumConnectionDurationClosesAndTerminallyCapturesAnAuthoritativeConnection() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var offloader = new DelayedFinalAcknowledgementOffloader();
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofMillis(5),
                    new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED)
                )
            );

            channel.advanceTimeBy(6, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, offloader.closeObservations.get());
            offloader.finalAcknowledgement.complete(null);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 32)
    void failClosedPolicyDoesNotForwardAMutationWhenOffloadFails() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);

        try (var rootContext = new TestRootContext()) {
            var failingStreamManager = new FailingStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer("Test", "c", failingStreamManager);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "c",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> true,
                    Duration.ZERO,
                    new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED)
                )
            );

            channel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            channel.runPendingTasks();

            Assertions.assertTrue(channel.inboundMessages().isEmpty());
            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, failingStreamManager.flushCount.get());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void failClosedTransitionStopsAnotherExistingConnectionBeforeItCanForward() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);

        try (var rootContext = new TestRootContext()) {
            var captureProcessState = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
            var failingStreamManager = new FailingStreamManager();
            var firstOffloader =
                new StreamChannelConnectionCaptureSerializer("Test", "first", failingStreamManager);
            var secondStreamManager = new TestStreamManager();
            var secondOffloader =
                new StreamChannelConnectionCaptureSerializer("Test", "second", secondStreamManager);
            var firstChannel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "first",
                    ctx -> firstOffloader,
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );
            var secondChannel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "n",
                    "second",
                    ctx -> secondOffloader,
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            firstChannel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            firstChannel.runPendingTasks();
            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());

            secondChannel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            secondChannel.runPendingTasks();

            Assertions.assertTrue(secondChannel.inboundMessages().isEmpty());
            Assertions.assertFalse(secondChannel.isOpen());
            Assertions.assertEquals(0, secondStreamManager.flushCount.get());
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

}
