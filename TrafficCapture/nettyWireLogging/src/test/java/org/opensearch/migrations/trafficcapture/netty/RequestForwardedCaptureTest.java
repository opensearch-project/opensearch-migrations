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
            source.completeNextWrite(channel, Instant.now());

            Assertions.assertEquals(List.of("read", "eom", "forwarded"), offloader.kinds());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void onlyTheWriteThatCompletesARequestIsCaptured() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var channel = channel(rootContext, offloader, source, new RequestCapturePredicate(), false);
            // As in the proxy, the client is read again only after the previous write to the source completes.
            channel.writeInbound(ascii("POST /thing HTTP/1.1\r\nContent-Length: 2\r\n\r\n"));
            source.completeNextWrite(channel, Instant.now());
            Assertions.assertEquals(List.of("read"), offloader.kinds());

            channel.writeInbound(ascii("ab"));
            source.completeNextWrite(channel, Instant.now());
            Assertions.assertEquals(List.of("read", "read", "eom", "forwarded"), offloader.kinds());
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void eachRequestOnAConnectionGetsItsOwnForwardInOrder() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var source = new SourceWrites();
            var channel = channel(rootContext, offloader, source, new RequestCapturePredicate(), false);
            channel.writeInbound(ascii(GET));
            source.completeNextWrite(channel, Instant.now());
            channel.writeOutbound(ascii("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"));
            channel.writeInbound(ascii(POST));
            source.completeNextWrite(channel, Instant.now());

            Assertions.assertEquals(
                List.of("read", "eom", "forwarded", "write", "read", "eom", "forwarded"),
                offloader.kinds()
            );
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

    }

    private static final class RecordingOffloader extends NoopChannelConnectionCaptureSerializer<Void> {
        private final List<String> kinds = new ArrayList<>();

        List<String> kinds() {
            return kinds;
        }

        @Override
        public void addReadEvent(Instant timestamp, ByteBuf buffer) {
            kinds.add("read");
        }

        @Override
        public void commitEndOfHttpMessageIndicator(Instant timestamp) {
            kinds.add("eom");
        }

        @Override
        public void addRequestForwardedEvent(Instant timestamp) {
            kinds.add("forwarded");
        }

        @Override
        public void addWriteEvent(Instant timestamp, ByteBuf buffer) {
            kinds.add("write");
        }

        @Override
        public void cancelCaptureForCurrentRequest(Instant timestamp) {
            kinds.add("dropped");
        }

        @Override
        public void addCloseEvent(Instant timestamp) {
            kinds.add("close");
        }
    }
}
