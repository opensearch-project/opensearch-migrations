package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestForwardedCaptureTest {
    private static final String GET = "GET /thing HTTP/1.1\r\nHost: source\r\n\r\n";
    private static final String POST = "POST /thing HTTP/1.1\r\nContent-Length: 2\r\n\r\nab";

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void forwardIsCapturedAfterTheEndOfMessageWhenTheRequestsLastWriteCompletes(boolean blockOnCapture)
        throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var channel = channel(rootContext, offloader, source, new RequestCapturePredicate(), blockOnCapture);
            channel.writeInbound(ascii(blockOnCapture ? POST : GET));

            Assertions.assertEquals(List.of("read", "eom"), offloader.kinds());
            var forwardedAt = Instant.now();
            source.completeNextWrite(channel, forwardedAt);

            Assertions.assertEquals(List.of("read", "eom", "forwarded"), offloader.kinds());
            var forwarded = offloader.events.get(2);
            Assertions.assertEquals(forwardedAt, forwarded.timestamp());
            Assertions.assertFalse(forwarded.timestamp().isBefore(offloader.events.get(0).timestamp()));
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void onlyTheWriteThatCompletesARequestIsCaptured() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var channel = channel(rootContext, offloader, source, new RequestCapturePredicate(), false);
            channel.writeInbound(ascii("POST /thing HTTP/1.1\r\nContent-Length: 2\r\n\r\n"));
            channel.writeInbound(ascii("ab"));

            var headWrittenAt = Instant.now();
            source.completeNextWrite(channel, headWrittenAt);
            Assertions.assertEquals(List.of("read", "read", "eom"), offloader.kinds());

            var bodyWrittenAt = headWrittenAt.plusMillis(3);
            source.completeNextWrite(channel, bodyWrittenAt);
            Assertions.assertEquals(List.of("read", "read", "eom", "forwarded"), offloader.kinds());
            Assertions.assertEquals(bodyWrittenAt, offloader.events.get(3).timestamp());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void eachRequestOnAConnectionGetsItsOwnForwardInOrder() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var channel = channel(rootContext, offloader, source, new RequestCapturePredicate(), false);
            var first = Instant.now();
            var second = first.plusMillis(5);

            channel.writeInbound(ascii(GET));
            source.completeNextWrite(channel, first);
            channel.writeOutbound(ascii("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"));
            channel.writeInbound(ascii(POST));
            source.completeNextWrite(channel, second);

            Assertions.assertEquals(
                List.of("read", "eom", "forwarded", "write", "read", "eom", "forwarded"),
                offloader.kinds()
            );
            Assertions.assertEquals(first, offloader.events.get(2).timestamp());
            Assertions.assertEquals(second, offloader.events.get(6).timestamp());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aRequestWhoseCaptureIsSuppressedHasNoForward() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var suppressHealthChecks = HeaderValueFilteringCapturePredicate.builder()
                .suppressCaptureHeaderPairs(Map.of("user-Agent", ".*uploader.*"))
                .build();
            var channel = channel(rootContext, offloader, source, suppressHealthChecks, false);
            channel.writeInbound(ascii(SimpleRequests.HEALTH_CHECK));
            source.completeNextWrite(channel, Instant.now());

            Assertions.assertFalse(offloader.kinds().contains("forwarded"));
            Assertions.assertFalse(offloader.kinds().contains("eom"));
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aWriteThatNeverCompletesHasNoForward() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var channel = channel(rootContext, offloader, source, new RequestCapturePredicate(), false);
            channel.writeInbound(ascii(GET));
            channel.close();
            channel.runPendingTasks();

            Assertions.assertEquals(List.of("read", "eom", "close"), offloader.kinds());
            source.releaseAll();
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
        TestRootContext rootContext,
        RecordingOffloader offloader,
        SourceWrites source,
        RequestCapturePredicate capturePredicate,
        boolean blockOnCapture
    ) throws IOException {
        return new EmbeddedChannel(
            new ConditionallyReliableLoggingHttpHandler<>(
                rootContext,
                "node",
                "connection",
                ignored -> offloader,
                capturePredicate,
                request -> blockOnCapture,
                new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN)
            ),
            source
        );
    }

    private static ByteBuf ascii(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII);
    }

    /** Stands in for the proxy's forwarding handler: holds each forwarded message until its write completes. */
    private static final class SourceWrites extends ChannelInboundHandlerAdapter {
        private final ArrayDeque<Object> pending = new ArrayDeque<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            pending.add(msg);
        }

        void completeNextWrite(EmbeddedChannel channel, Instant completedAt) {
            ReferenceCountUtil.release(pending.remove());
            SourceForwardingCompletion.reportWriteCompleted(channel, completedAt);
        }

        void releaseAll() {
            pending.forEach(ReferenceCountUtil::release);
            pending.clear();
        }
    }

    private record Event(String kind, Instant timestamp) {}

    private static final class RecordingOffloader extends NoopChannelConnectionCaptureSerializer<Void> {
        private final List<Event> events = new ArrayList<>();

        List<String> kinds() {
            return events.stream().map(Event::kind).toList();
        }

        @Override
        public void addReadEvent(Instant timestamp, ByteBuf buffer) {
            events.add(new Event("read", timestamp));
        }

        @Override
        public void commitEndOfHttpMessageIndicator(Instant timestamp) {
            events.add(new Event("eom", timestamp));
        }

        @Override
        public void addRequestForwardedEvent(Instant timestamp) {
            events.add(new Event("forwarded", timestamp));
        }

        @Override
        public void addWriteEvent(Instant timestamp, ByteBuf buffer) {
            events.add(new Event("write", timestamp));
        }

        @Override
        public void cancelCaptureForCurrentRequest(Instant timestamp) {
            events.add(new Event("dropped", timestamp));
        }

        @Override
        public void addCloseEvent(Instant timestamp) {
            events.add(new Event("close", timestamp));
        }
    }
}
