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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Slf4j
@WrapWithNettyLeakDetection
public class ConditionallyReliableLoggingHttpHandlerTest {

    private enum RequiredCaptureFailurePoint {
        ADD_READ,
        CANCEL_REQUEST,
        END_FIRST_LINE,
        END_HEADERS,
        END_MESSAGE,
        ADD_WRITE
    }

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
    void aggregateHeaderByteLimitStopsOneByteAtATimeHeaderGrowth() throws Exception {
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
            var request = (
                "GET / HTTP/1.1\r\nX-Large: 1234567890123456789012345678901234567890\r\n\r\n"
            ).getBytes(StandardCharsets.US_ASCII);

            writeOneByteAtATimeUntilClosed(channel, request);
            channel.runPendingTasks();

            assertIncrementalBoundViolationClosesBeforeTheRequestCompletes(
                channel,
                streamManager,
                request.length
            );
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

    @Test
    void aggregateTotalRequestByteLimitStopsOneByteAtATimeBodyGrowth() throws Exception {
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

            writeOneByteAtATimeUntilClosed(channel, request);
            channel.runPendingTasks();

            assertIncrementalBoundViolationClosesBeforeTheRequestCompletes(
                channel,
                streamManager,
                request.length
            );
        }
    }

    @Test
    void aggregateRequestByteCounterResetsBetweenKeepAliveRequests() throws Exception {
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
                    new IncompleteRequestLimits(Duration.ofMinutes(1), 32, 32),
                    failOpenCaptureState()
                )
            );

            Assertions.assertTrue(
                channel.writeInbound(Unpooled.copiedBuffer("GET /one HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII))
            );
            Assertions.assertTrue(
                channel.writeInbound(Unpooled.copiedBuffer("GET /two HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII))
            );
            channel.runPendingTasks();

            Assertions.assertTrue(channel.isOpen());
            channel.close();
            channel.finishAndReleaseAll();
        }
    }

    private static void writeOneByteAtATimeUntilClosed(EmbeddedChannel channel, byte[] request) {
        for (var value : request) {
            if (!channel.isOpen()) {
                return;
            }
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { value }));
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

    private static void assertIncrementalBoundViolationClosesBeforeTheRequestCompletes(
        EmbeddedChannel channel,
        TestStreamManager streamManager,
        int completeRequestBytes
    ) throws Exception {
        Assertions.assertFalse(channel.isOpen());
        var forwardedBytes = channel.inboundMessages()
            .stream()
            .mapToInt(message -> ((ByteBuf) message).readableBytes())
            .sum();
        Assertions.assertTrue(forwardedBytes > 0);
        Assertions.assertTrue(forwardedBytes < completeRequestBytes);
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

    private static class DiagnosticFailureOffloader extends NoopChannelConnectionCaptureSerializer<Void> {
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
        extends NoopChannelConnectionCaptureSerializer<Void> {
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

    private static class CloseObservationFailureOffloader
        extends NoopChannelConnectionCaptureSerializer<Void> {
        private final Throwable failure;

        private CloseObservationFailureOffloader(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void addCloseEvent(Instant timestamp) throws IOException {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (IOException) failure;
        }
    }

    private static class FinalFlushFailureOffloader
        extends NoopChannelConnectionCaptureSerializer<Void> {
        private final AtomicInteger closeObservations = new AtomicInteger();
        private final Throwable failure;

        private FinalFlushFailureOffloader(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void addCloseEvent(Instant timestamp) {
            closeObservations.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> flushCommitAndResetStream(boolean isFinal) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static class BlockingFlushFailureOffloader
        extends NoopChannelConnectionCaptureSerializer<Void> {
        private final AtomicInteger flushes = new AtomicInteger();
        private final Throwable failure;

        private BlockingFlushFailureOffloader(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public CompletableFuture<Void> flushCommitAndResetStream(boolean isFinal) {
            flushes.incrementAndGet();
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static class RequiredCaptureFailureOffloader
        extends NoopChannelConnectionCaptureSerializer<Void> {
        private final RequiredCaptureFailurePoint failurePoint;
        private final Throwable failure;

        private RequiredCaptureFailureOffloader(RequiredCaptureFailurePoint failurePoint) {
            this(failurePoint, new IOException("Required capture failed at " + failurePoint));
        }

        private RequiredCaptureFailureOffloader(
            RequiredCaptureFailurePoint failurePoint,
            Throwable failure
        ) {
            this.failurePoint = failurePoint;
            this.failure = failure;
        }

        private void failAt(RequiredCaptureFailurePoint point) throws IOException {
            if (failurePoint == point) {
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (IOException) failure;
            }
        }

        @Override
        public void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {
            failAt(RequiredCaptureFailurePoint.ADD_READ);
        }

        @Override
        public void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException {
            failAt(RequiredCaptureFailurePoint.CANCEL_REQUEST);
        }

        @Override
        public void addEndOfFirstLineIndicator(int characterIndex) throws IOException {
            failAt(RequiredCaptureFailurePoint.END_FIRST_LINE);
        }

        @Override
        public void addEndOfHeadersIndicator(int characterIndex) throws IOException {
            failAt(RequiredCaptureFailurePoint.END_HEADERS);
        }

        @Override
        public void commitEndOfHttpMessageIndicator(Instant timestamp) throws IOException {
            failAt(RequiredCaptureFailurePoint.END_MESSAGE);
        }

        @Override
        public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {
            failAt(RequiredCaptureFailurePoint.ADD_WRITE);
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
    void failOpenForwardsTheWaitingMutationWhenItsAcknowledgementFailsValidation() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);

        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>(
                "Test",
                "connection",
                null,
                () -> 0,
                streamManager,
                ignored -> {
                    throw new IllegalStateException("manifest freshness expired");
                }
            );
            var captureProcessState = new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext,
                    "node",
                    "connection",
                    ignored -> offloader,
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            channel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            channel.runPendingTasks();

            Assertions.assertTrue(captureProcessState.isPassThrough());
            Assertions.assertTrue(channel.isOpen());
            var firstForwardedMessage = (ByteBuf) channel.readInbound();
            try {
                Assertions.assertEquals(fullTrafficBytes.length, firstForwardedMessage.readableBytes());
            } finally {
                firstForwardedMessage.release();
            }
            Assertions.assertEquals(1, streamManager.flushCount.get());

            channel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            channel.runPendingTasks();
            var secondForwardedMessage = (ByteBuf) channel.readInbound();
            try {
                Assertions.assertEquals(fullTrafficBytes.length, secondForwardedMessage.readableBytes());
            } finally {
                secondForwardedMessage.release();
            }
            Assertions.assertEquals(
                1,
                streamManager.flushCount.get(),
                "The connection must remain permanently uncaptured after entering pass-through"
            );
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void failClosedRejectsTheWaitingMutationWhenItsAcknowledgementFailsValidation() throws IOException {
        byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);

        try (var rootContext = new TestRootContext()) {
            var streamManager = new TestStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>(
                "Test",
                "connection",
                null,
                () -> 0,
                streamManager,
                ignored -> {
                    throw new IllegalStateException("manifest freshness expired");
                }
            );
            var captureProcessState = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext,
                    "node",
                    "connection",
                    ignored -> offloader,
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            channel.writeInbound(Unpooled.wrappedBuffer(fullTrafficBytes));
            channel.runPendingTasks();

            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertFalse(channel.isOpen());
            Assertions.assertTrue(channel.inboundMessages().isEmpty());
            Assertions.assertEquals(1, streamManager.flushCount.get());
            channel.finishAndReleaseAll();
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
    void sourceActivityDoesNotExtendTheMaximumConnectionDuration() throws Exception {
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
                    Duration.ofMillis(10),
                    new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED)
                )
            );

            channel.advanceTimeBy(6, java.util.concurrent.TimeUnit.MILLISECONDS);
            Assertions.assertTrue(
                channel.writeInbound(Unpooled.copiedBuffer("GET / HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII))
            );
            channel.advanceTimeBy(5, java.util.concurrent.TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();

            Assertions.assertFalse(channel.isOpen());
            Assertions.assertEquals(1, offloader.closeObservations.get());
            offloader.finalAcknowledgement.complete(null);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closeObservationFailureInvokesTheCaptureFailurePolicyExactlyOnce() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                ignored -> transitions.incrementAndGet()
            );
            var offloader = new CloseObservationFailureOffloader(
                new IOException("terminal observation failed")
            );
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            channel.close();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();

            Assertions.assertEquals(CaptureProcessState.State.PASS_THROUGH, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
        }
    }

    @Test
    void failedTerminalFlushInvokesTheCaptureFailurePolicyExactlyOnce() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_CLOSED,
                ignored -> transitions.incrementAndGet()
            );
            var offloader = new FinalFlushFailureOffloader(
                new IOException("terminal Kafka publication failed")
            );
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            channel.close();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();

            Assertions.assertEquals(1, offloader.closeObservations.get());
            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
        }
    }

    @Test
    void errorWhileWritingCloseObservationAlwaysTerminatesTheProcess() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                ignored -> transitions.incrementAndGet()
            );
            var failure = new AssertionError("terminal capture owner failed");
            var offloader = new CloseObservationFailureOffloader(failure);
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> false,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            channel.close();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();

            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
        }
    }

    @ParameterizedTest
    @EnumSource(RequiredCaptureFailurePoint.class)
    void synchronousRequiredCaptureFailureAppliesTheProcessWideFailOpenPolicy(
        RequiredCaptureFailurePoint failurePoint
    ) throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                ignored -> transitions.incrementAndGet()
            );
            var offloader = new RequiredCaptureFailureOffloader(failurePoint);
            var requestCapturePredicate = failurePoint == RequiredCaptureFailurePoint.CANCEL_REQUEST
                ? new RequestCapturePredicate() {
                    @Override
                    public CaptureDirective apply(io.netty.handler.codec.http.HttpRequest request) {
                        return CaptureDirective.DROP;
                    }
                }
                : new RequestCapturePredicate();
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    requestCapturePredicate,
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            if (failurePoint == RequiredCaptureFailurePoint.ADD_WRITE) {
                var response = Unpooled.wrappedBuffer("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                Assertions.assertTrue(channel.writeOutbound(response));
            } else if (failurePoint == RequiredCaptureFailurePoint.CANCEL_REQUEST) {
                Assertions.assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 'G' })));
                Assertions.assertTrue(
                    channel.writeInbound(
                        Unpooled.wrappedBuffer("ET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8))
                    )
                );
            } else {
                Assertions.assertTrue(
                    channel.writeInbound(
                        Unpooled.wrappedBuffer(SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8))
                    )
                );
            }
            channel.runPendingTasks();

            Assertions.assertEquals(CaptureProcessState.State.PASS_THROUGH, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
            Assertions.assertTrue(channel.isOpen());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void synchronousRequiredCaptureFailureAppliesTheProcessWideFailClosedPolicy() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_CLOSED,
                ignored -> transitions.incrementAndGet()
            );
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> new RequiredCaptureFailureOffloader(RequiredCaptureFailurePoint.ADD_READ),
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            Assertions.assertFalse(
                channel.writeInbound(
                    Unpooled.wrappedBuffer(SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8))
                )
            );
            channel.runPendingTasks();

            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
            Assertions.assertFalse(channel.isOpen());
            Assertions.assertTrue(channel.inboundMessages().isEmpty());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void errorDuringARequiredCaptureOperationAlwaysTerminatesTheProcess() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                ignored -> transitions.incrementAndGet()
            );
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> new RequiredCaptureFailureOffloader(
                        RequiredCaptureFailurePoint.ADD_READ,
                        new AssertionError("capture owner failed")
                    ),
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            Assertions.assertFalse(
                channel.writeInbound(
                    Unpooled.wrappedBuffer(SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8))
                )
            );
            channel.runPendingTasks();

            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
            Assertions.assertFalse(channel.isOpen());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void errorFromBlockedRequestFlushAlwaysTerminatesAndClosesTheConnection() throws Exception {
        try (var rootContext = new TestRootContext()) {
            var transitions = new AtomicInteger();
            var captureProcessState = new CaptureProcessState(
                CaptureFailurePolicy.FAIL_OPEN,
                ignored -> transitions.incrementAndGet()
            );
            var offloader = new BlockingFlushFailureOffloader(
                new AssertionError("capture acknowledgement owner failed")
            );
            var channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler(
                    rootContext,
                    "node",
                    "connection",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    request -> true,
                    IncompleteRequestLimits.DEFAULT,
                    Duration.ofHours(1),
                    captureProcessState
                )
            );

            Assertions.assertFalse(
                channel.writeInbound(
                    Unpooled.wrappedBuffer(SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8))
                )
            );
            channel.runPendingTasks();

            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertEquals(1, transitions.get());
            Assertions.assertFalse(channel.isOpen());
            Assertions.assertTrue(channel.inboundMessages().isEmpty());
            Assertions.assertTrue(offloader.flushes.get() >= 1);
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
