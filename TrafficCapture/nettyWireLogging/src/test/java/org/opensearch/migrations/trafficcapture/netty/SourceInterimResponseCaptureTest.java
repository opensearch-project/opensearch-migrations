/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.netty.tracing.IWireCaptureContexts;
import org.opensearch.migrations.trafficcapture.netty.tracing.WireCaptureContexts;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceInterimResponseCaptureTest {

    @Test
    void capturesOneHundredOneHundredTwoOneHundredThreeAndMultipleInterimsAsTypedEvents() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var channel = channel(rootContext, offloader);
            channel.writeInbound(ascii(
                "POST /thing HTTP/1.1\r\nContent-Length: 2\r\n\r\na"
            ));
            channel.writeOutbound(ascii("HTTP/1.1 100 Cont"));
            channel.writeOutbound(ascii("inue\r\n\r\n"));
            channel.writeInbound(ascii("b"));
            channel.writeOutbound(ascii(
                "HTTP/1.1 102 Processing\r\n\r\n"
                    + "HTTP/1.1 103 Early Hints\r\n\r\n"
                    + "HTTP/1.1 103 Early Hints\r\nLink: </next>\r\n\r\n"
                    + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            ));

            Assertions.assertEquals(
                List.of(
                    "HTTP/1.1 100 Continue\r\n\r\n",
                    "HTTP/1.1 102 Processing\r\n\r\n",
                    "HTTP/1.1 103 Early Hints\r\n\r\n",
                    "HTTP/1.1 103 Early Hints\r\nLink: </next>\r\n\r\n"
                ),
                offloader.interimResponses
            );
            Assertions.assertEquals(
                List.of("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"),
                offloader.finalWrites
            );
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "HTTP/1.1 101 Switching Protocols\r\n\r\n",
        "HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\n\r\n",
        "HTTP/1.1 417 Expectation Failed\r\nContent-Length: 0\r\n\r\n"
    })
    void switchingProtocolsAndRejectionsRemainOrdinaryFinalWrites(String response) throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var channel = channel(rootContext, offloader);
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            channel.writeOutbound(ascii(response));

            Assertions.assertTrue(offloader.interimResponses.isEmpty());
            Assertions.assertEquals(List.of(response), offloader.finalWrites);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void bytesHeldWithNoHeaderEndBecomeFinalWritesBeforeTheExceptionAndClose() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var channel = channel(rootContext, offloader);
            channel.pipeline().addLast(new ExceptionConsumingHandler());
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            var heldBytes = "HTTP/1.1 100 Cont";
            channel.writeOutbound(ascii(heldBytes));
            Assertions.assertEquals(List.of(), offloader.events);

            channel.pipeline().fireExceptionCaught(new IOException("source connection broke"));
            channel.runPendingTasks();

            Assertions.assertEquals(
                List.of("write:" + heldBytes, "exception:source connection broke", "close"),
                offloader.events
            );
            Assertions.assertTrue(offloader.interimResponses.isEmpty());
            channel.releaseOutbound();
            channel.releaseInbound();
        }
    }

    @Test
    void bytesWrittenCountsInterimAndFinalResponseBytesExactlyOnce() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext(true, true)) {
            var channel = channel(rootContext, offloader);
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            channel.writeOutbound(ascii("HTTP/1.1 100 Cont"));
            channel.writeOutbound(ascii("inue\r\n\r\nHTTP/1.1 200 OK\r\n\r\n"));

            Assertions.assertEquals(
                "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n\r\n".length(),
                bytesWritten(rootContext)
            );
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void bytesWrittenCountsBytesFlushedWhenTheConnectionEndsWithNoHeaderEnd() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext(true, true)) {
            var channel = channel(rootContext, offloader);
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            var heldBytes = "HTTP/1.1 100 Cont";
            channel.writeOutbound(ascii(heldBytes));
            channel.close();
            channel.runPendingTasks();

            Assertions.assertEquals(List.of("write:" + heldBytes, "close"), offloader.events);
            Assertions.assertEquals(heldBytes.length(), bytesWritten(rootContext));
            channel.releaseOutbound();
            channel.releaseInbound();
        }
    }

    @Test
    void telemetryFailureEndingTheConnectionStillCapturesTheFlushAndCloseEvents() throws Exception {
        var offloader = new RecordingOffloader();
        var captureProcessState = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
        try (var rootContext = new FailFirstResponseInstrumentsRootContext()) {
            var channel = channel(rootContext, offloader, captureProcessState);
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            var heldBytes = "HTTP/1.1 100 Cont";
            channel.writeOutbound(ascii(heldBytes));
            channel.close();
            channel.runPendingTasks();

            Assertions.assertEquals(List.of("write:" + heldBytes, "close"), offloader.events);
            Assertions.assertEquals(CaptureProcessState.State.CAPTURE, captureProcessState.state());
            Assertions.assertEquals(1, rootContext.responseInstrumentFailures());
            channel.releaseOutbound();
            channel.releaseInbound();
        }
    }

    @Test
    void exceptionFlushFailureUsesTheRequiredCaptureFailurePolicy() throws Exception {
        var offloader = new RecordingOffloader();
        offloader.failFinalWrites = true;
        var captureProcessState = new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED);
        try (var rootContext = new TestRootContext()) {
            var channel = channel(rootContext, offloader, captureProcessState);
            channel.pipeline().addLast(new ExceptionConsumingHandler());
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            channel.writeOutbound(ascii("HTTP/1.1 100 Cont"));

            channel.pipeline().fireExceptionCaught(new IOException("source connection broke"));
            channel.runPendingTasks();

            Assertions.assertEquals(CaptureProcessState.State.TERMINATING, captureProcessState.state());
            Assertions.assertFalse(channel.isOpen());
            Assertions.assertTrue(offloader.events.isEmpty());
            channel.releaseOutbound();
            channel.releaseInbound();
        }
    }

    private static long bytesWritten(TestRootContext rootContext) {
        return InMemoryInstrumentationBundle.getMetricValueOrZero(
            rootContext.instrumentationBundle.getFinishedMetrics(),
            IWireCaptureContexts.MetricNames.BYTES_WRITTEN
        );
    }

    private static EmbeddedChannel channel(TestRootContext rootContext, RecordingOffloader offloader)
        throws IOException {
        return channel(
            rootContext,
            offloader,
            new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN)
        );
    }

    private static EmbeddedChannel channel(
        TestRootContext rootContext,
        RecordingOffloader offloader,
        CaptureProcessState captureProcessState
    ) throws IOException {
        return new EmbeddedChannel(
            new ConditionallyReliableLoggingHttpHandler<>(
                rootContext,
                "node",
                "connection",
                ignored -> offloader,
                new RequestCapturePredicate(),
                request -> false,
                captureProcessState
            )
        );
    }

    private static ByteBuf ascii(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII);
    }

    private static final class RecordingOffloader extends NoopChannelConnectionCaptureSerializer<Void> {
        private final List<String> interimResponses = new ArrayList<>();
        private final List<String> finalWrites = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean failFinalWrites;

        @Override
        public void addInterimResponseEvent(Instant timestamp, ByteBuf buffer) {
            var bytes = asString(buffer);
            interimResponses.add(bytes);
            events.add("interim:" + bytes);
        }

        @Override
        public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {
            if (failFinalWrites) {
                throw new IOException("final write capture failed");
            }
            var bytes = asString(buffer);
            finalWrites.add(bytes);
            events.add("write:" + bytes);
        }

        @Override
        public void addExceptionCaughtEvent(Instant timestamp, Throwable t) {
            events.add("exception:" + t.getMessage());
        }

        @Override
        public void addCloseEvent(Instant timestamp) {
            events.add("close");
        }

        private static String asString(ByteBuf buffer) {
            return buffer.toString(
                buffer.readerIndex(),
                buffer.readableBytes(),
                StandardCharsets.US_ASCII
            );
        }
    }

    private static final class ExceptionConsumingHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // The test inspects the capture observation order directly.
        }
    }

    /**
     * Fails the first response-metric lookup, which is the one the end-of-connection client byte
     * count performs. Later lookups succeed so that closing the response scope still works.
     */
    private static final class FailFirstResponseInstrumentsRootContext extends TestRootContext {
        private int failures;

        @Override
        public WireCaptureContexts.ResponseContext.MetricInstruments getResponseInstruments() {
            if (failures == 0) {
                failures++;
                throw new IllegalStateException("response instrument lookup failed");
            }
            return super.getResponseInstruments();
        }

        private int responseInstrumentFailures() {
            return failures;
        }
    }
}
