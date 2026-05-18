package org.opensearch.migrations.trafficcapture.netty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamAndByteBufferWrapper;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that 1xx interim responses (e.g. 100 Continue) sent during the
 * request phase are captured as InterimResponse observations rather than
 * Write observations, while final responses (including 101 Switching Protocols
 * and 4xx/5xx rejections sent in lieu of 100 Continue) remain Writes.
 */
@Slf4j
@WrapWithNettyLeakDetection
public class HundredContinueCaptureTest {

    private static final String REQ_HEADERS_EXPECT = "POST /x HTTP/1.1\r\nHost: l\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n";
    private static final String REQ_BODY = "hello";
    private static final String FINAL_200 = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
    private static final String CONTINUE_100 = "HTTP/1.1 100 Continue\r\n\r\n";
    private static final String PROCESSING_102 = "HTTP/1.1 102 Processing\r\n\r\n";
    private static final String EARLY_HINTS_103_A = "HTTP/1.1 103 Early Hints\r\nLink: </a.css>; rel=preload\r\n\r\n";
    private static final String EARLY_HINTS_103_B = "HTTP/1.1 103 Early Hints\r\nLink: </b.js>; rel=preload\r\n\r\n";
    private static final String SWITCHING_101 = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n";
    private static final String UPGRADE_REQ = "GET /chat HTTP/1.1\r\nHost: l\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n";
    private static final String REJECT_417 = "HTTP/1.1 417 Expectation Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    private static final String REJECT_413 = "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    private static final String GET_REQ = "GET / HTTP/1.1\r\nHost: l\r\nAccept: */*\r\n\r\n";

    private static final boolean IN = true;
    private static final boolean OUT = false;

    /** A step in a wire exchange: true for inbound (client->proxy), false for outbound. */
    record Step(boolean inbound, String bytes) {
        static Step in(String s) { return new Step(IN, s); }
        static Step out(String s) { return new Step(OUT, s); }
    }

    @SneakyThrows
    private static List<TrafficObservation> capture(Step... steps) {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new AccumulatingStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);
            var channel = new EmbeddedChannel(new ConditionallyReliableLoggingHttpHandler<>(
                rootContext, "n", "c", ctx -> offloader, new RequestCapturePredicate(), x -> true));
            try {
                for (var step : steps) {
                    var buf = Unpooled.wrappedBuffer(step.bytes().getBytes(StandardCharsets.UTF_8));
                    if (step.inbound()) channel.writeInbound(buf); else channel.writeOutbound(buf);
                }
            } catch (Exception e) {
                log.warn("Exchange threw", e);
            }
            channel.finishAndReleaseAll();
            channel.close();
            var all = new ArrayList<TrafficObservation>();
            for (var bb : streamManager.flushedBuffers) {
                all.addAll(TrafficStream.parseFrom(bb).getSubStreamList());
            }
            return all;
        }
    }

    private static String dataAsString(java.util.function.Function<TrafficObservation, com.google.protobuf.ByteString> extract,
                                       List<TrafficObservation> obs) {
        return obs.stream().map(extract).filter(b -> b != null && b.size() > 0)
            .map(b -> b.toString(StandardCharsets.UTF_8)).collect(Collectors.joining());
    }

    private static String allReads(List<TrafficObservation> obs) {
        return dataAsString(o -> o.hasRead() ? o.getRead().getData() : null, obs);
    }

    private static String allWrites(List<TrafficObservation> obs) {
        return dataAsString(o -> o.hasWrite() ? o.getWrite().getData() : null, obs);
    }

    private static String allInterims(List<TrafficObservation> obs) {
        return dataAsString(o -> o.hasInterimResponse() ? o.getInterimResponse().getData() : null, obs);
    }

    private static int firstIndexOf(List<TrafficObservation> obs, java.util.function.Predicate<TrafficObservation> p) {
        for (int i = 0; i < obs.size(); i++) if (p.test(obs.get(i))) return i;
        return -1;
    }

    @Test
    public void hundredContinue_readsAndBodyArriveBeforeEom() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(CONTINUE_100),
            Step.in(REQ_BODY), Step.out(FINAL_200));
        var eomIdx = firstIndexOf(obs, TrafficObservation::hasEndOfMessageIndicator);
        Assertions.assertTrue(eomIdx > 0, () -> "expected EOM in: " + obs);
        for (int i = eomIdx + 1; i < obs.size(); i++) {
            Assertions.assertFalse(obs.get(i).hasRead(), "Read after EOM at " + i);
        }
        var reads = allReads(obs);
        Assertions.assertTrue(reads.contains("Expect: 100-continue"));
        Assertions.assertTrue(reads.endsWith(REQ_BODY));
    }

    @Test
    public void hundredContinue_noWriteBeforeEom_finalResponseIsWrite() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(CONTINUE_100),
            Step.in(REQ_BODY), Step.out(FINAL_200));
        var eomIdx = firstIndexOf(obs, TrafficObservation::hasEndOfMessageIndicator);
        for (int i = 0; i < eomIdx; i++) {
            Assertions.assertFalse(obs.get(i).hasWrite(), "Write before EOM at " + i);
        }
        Assertions.assertTrue(allWrites(obs).contains("HTTP/1.1 200 OK"));
    }

    @Test
    public void hundredContinue_capturedAsInterimResponse_beforeEom() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(CONTINUE_100),
            Step.in(REQ_BODY), Step.out(FINAL_200));
        Assertions.assertTrue(allInterims(obs).contains("HTTP/1.1 100 Continue"));
        var interimIdx = firstIndexOf(obs, TrafficObservation::hasInterimResponse);
        var eomIdx = firstIndexOf(obs, TrafficObservation::hasEndOfMessageIndicator);
        Assertions.assertTrue(interimIdx >= 0 && interimIdx < eomIdx,
            "interim must precede EOM: interim=" + interimIdx + " eom=" + eomIdx);
    }

    @Test
    public void processing_102_capturedAsInterimResponse() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(PROCESSING_102),
            Step.in(REQ_BODY), Step.out(FINAL_200));
        Assertions.assertTrue(allInterims(obs).contains("102 Processing"));
        Assertions.assertTrue(allWrites(obs).contains("HTTP/1.1 200 OK"));
    }

    @Test
    public void earlyHints_103_multipleCapturedAsSeparateInterimResponses() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT),
            Step.out(EARLY_HINTS_103_A), Step.out(EARLY_HINTS_103_B),
            Step.in(REQ_BODY), Step.out(FINAL_200));
        var interims = allInterims(obs);
        Assertions.assertTrue(interims.contains("a.css") && interims.contains("b.js"),
            () -> "both early hints must be captured: " + interims);
    }

    @Test
    public void switchingProtocols_101_isWriteNotInterim() {
        var obs = capture(Step.in(UPGRADE_REQ), Step.out(SWITCHING_101));
        Assertions.assertFalse(allInterims(obs).contains("101"),
            "101 must not be InterimResponse");
        Assertions.assertTrue(allWrites(obs).contains("101 Switching Protocols"));
    }

    @Test
    public void rejection_417_isWriteNotInterim() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(REJECT_417), Step.in(REQ_BODY));
        Assertions.assertTrue(allWrites(obs).contains("417 Expectation Failed"));
        Assertions.assertFalse(allInterims(obs).contains("417"));
    }

    @Test
    public void rejection_413_isWriteNotInterim() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(REJECT_413), Step.in(REQ_BODY));
        Assertions.assertTrue(allWrites(obs).contains("413 Payload Too Large"));
        Assertions.assertFalse(allInterims(obs).contains("413"));
    }

    @Test
    public void hundredContinue_thenRejection_capturedDistinctly() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(CONTINUE_100),
            Step.in(REQ_BODY), Step.out(REJECT_413));
        Assertions.assertTrue(allInterims(obs).contains("100 Continue"));
        Assertions.assertTrue(allWrites(obs).contains("413"));
        Assertions.assertFalse(allInterims(obs).contains("413"));
    }

    @Test
    public void requestAfterHundredContinue_isCapturedCleanly() {
        var obs = capture(
            Step.in(REQ_HEADERS_EXPECT), Step.out(CONTINUE_100),
            Step.in(REQ_BODY), Step.out(FINAL_200),
            Step.in(GET_REQ), Step.out(FINAL_200));
        var eomCount = obs.stream().filter(TrafficObservation::hasEndOfMessageIndicator).count();
        Assertions.assertEquals(2, eomCount, () -> "expected 2 EOMs: " + obs);
        obs.stream().filter(TrafficObservation::hasEndOfMessageIndicator).forEach(o -> {
            var eom = o.getEndOfMessageIndicator();
            Assertions.assertTrue(eom.getFirstLineByteLength() > 0 && eom.getHeadersByteLength() > 0);
        });
    }

    /** Stream manager that retains every flushed buffer (handler flushes between requests). */
    static class AccumulatingStreamManager extends OrderedStreamLifecyleManager implements AutoCloseable {
        final List<ByteBuffer> flushedBuffers = new ArrayList<>();
        final AtomicInteger flushCount = new AtomicInteger();

        @Override public void close() {}

        @Override public CodedOutputStreamAndByteBufferWrapper createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(1024 * 1024);
        }

        @SneakyThrows
        @Override
        public CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder holder, int index) {
            var osh = (CodedOutputStreamAndByteBufferWrapper) holder;
            osh.getOutputStream().flush();
            flushedBuffers.add(osh.getByteBuffer().flip().asReadOnlyBuffer());
            return CompletableFuture.completedFuture(flushCount.incrementAndGet());
        }
    }
}
