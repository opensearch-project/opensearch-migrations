package org.opensearch.migrations.trafficcapture.netty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression test for LoggingHttpHandler's response-side EOM detection with a HEAD response.
 *
 * A HEAD response can carry a Content-Length header describing what the body WOULD have been
 * for the equivalent GET, but sends no actual body bytes. A plain HttpResponseDecoder (unlike
 * HttpClientCodec) has no notion of the originating request's method, so without
 * SimpleHttpResponseDecoder's request-method queue it would wait indefinitely for body bytes
 * that never arrive — never firing that response's own EOM/flush — and then misparse the next
 * response's bytes on the same connection as the tail of that phantom body.
 */
@Slf4j
@WrapWithNettyLeakDetection
public class HeadResponseCaptureTest {

    private static final String HEAD_REQUEST = "HEAD /resource HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "\r\n";

    // Declares a 5-byte body (as it would for the equivalent GET) but — correctly, per HTTP
    // semantics for HEAD — sends none. This is exactly the case a bare HttpResponseDecoder can't
    // detect on its own.
    private static final String HEAD_RESPONSE = "HTTP/1.1 200 OK\r\n"
        + "Content-Length: 5\r\n"
        + "\r\n";

    private static final String GET_REQUEST = "GET /other HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "\r\n";

    private static final String GET_RESPONSE = "HTTP/1.1 200 OK\r\n"
        + "Content-Length: 2\r\n"
        + "\r\n"
        + "OK";

    /**
     * A stream manager that accumulates every flushed buffer rather than only the last one, since
     * this test expects one explicit flush per response (LoggingHttpHandler.write() flushes
     * immediately on each response's EOM).
     */
    static class AccumulatingStreamManager extends OrderedStreamLifecyleManager implements AutoCloseable {
        final List<ByteBuffer> flushedBuffers = new ArrayList<>();
        final AtomicInteger flushCount = new AtomicInteger();

        @Override
        public void close() {}

        @Override
        public CodedOutputStreamAndByteBufferWrapper createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(1024 * 1024);
        }

        @SneakyThrows
        @Override
        public CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            CodedOutputStream cos = osh.getOutputStream();
            cos.flush();
            flushedBuffers.add(osh.getByteBuffer().flip().asReadOnlyBuffer());
            return CompletableFuture.completedFuture(flushCount.incrementAndGet());
        }
    }

    private static String describeObservations(List<TrafficObservation> observations) {
        StringBuilder sb = new StringBuilder();
        sb.append("observation count: ").append(observations.size()).append("\n");
        for (int i = 0; i < observations.size(); i++) {
            var obs = observations.get(i);
            if (obs.hasRead()) {
                sb.append("  [").append(i).append("] Read(").append(obs.getRead().getData().size()).append("b)\n");
            } else if (obs.hasWrite()) {
                sb.append("  [").append(i).append("] Write(").append(obs.getWrite().getData().size()).append("b)\n");
            } else if (obs.hasEndOfMessageIndicator()) {
                var eom = obs.getEndOfMessageIndicator();
                sb.append("  [").append(i).append("] EOM(firstLine=")
                    .append(eom.getFirstLineByteLength())
                    .append(", headers=").append(eom.getHeadersByteLength()).append(")\n");
            } else {
                sb.append("  [").append(i).append("] ").append(obs.getCaptureCase()).append("\n");
            }
        }
        return sb.toString();
    }

    @Test
    public void testHeadResponseFollowedByNormalResponseOnSameConnection() throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new AccumulatingStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new LoggingHttpHandler<>(rootContext, "n", "c", ctx -> offloader, new RequestCapturePredicate())
            );

            channel.writeInbound(Unpooled.wrappedBuffer(HEAD_REQUEST.getBytes(StandardCharsets.UTF_8)));
            channel.writeOutbound(Unpooled.wrappedBuffer(HEAD_RESPONSE.getBytes(StandardCharsets.UTF_8)));
            channel.writeInbound(Unpooled.wrappedBuffer(GET_REQUEST.getBytes(StandardCharsets.UTF_8)));
            channel.writeOutbound(Unpooled.wrappedBuffer(GET_RESPONSE.getBytes(StandardCharsets.UTF_8)));

            channel.finishAndReleaseAll();
            channel.close();

            // Each response's EOM firing is what triggers its own explicit flush (see write()'s
            // flushCommitAndResetStream call), plus one more on connection teardown — without
            // HEAD-awareness, the HEAD response's EOM never fires, so this would be fewer.
            Assertions.assertEquals(3, streamManager.flushCount.get(),
                "Expected one flush per response (2) plus one on connection teardown — the HEAD "
                + "response's own EOM must fire immediately, not get stuck waiting for a body that "
                + "never arrives");

            List<TrafficObservation> allObservations = new ArrayList<>();
            for (var buf : streamManager.flushedBuffers) {
                var trafficStream = TrafficStream.parseFrom(buf);
                allObservations.addAll(trafficStream.getSubStreamList());
            }
            String debug = describeObservations(allObservations);

            // One EOM per request and one per response = 4 total.
            long eomCount = allObservations.stream().filter(TrafficObservation::hasEndOfMessageIndicator).count();
            Assertions.assertEquals(4, eomCount,
                "Expected 4 EndOfMessageIndicator observations (2 requests + 2 responses). Debug:\n" + debug);

            // The GET response's EOM must reflect its own real header size, not a corrupted/garbage
            // value left over from a decoder that thought it was still consuming the HEAD response's
            // phantom body.
            var eoms = allObservations.stream()
                .filter(TrafficObservation::hasEndOfMessageIndicator)
                .map(TrafficObservation::getEndOfMessageIndicator)
                .collect(Collectors.toList());
            for (int i = 0; i < eoms.size(); i++) {
                Assertions.assertTrue(eoms.get(i).getFirstLineByteLength() > 0,
                    "EOM[" + i + "] firstLineByteLength should be positive. Debug:\n" + debug);
                Assertions.assertTrue(eoms.get(i).getHeadersByteLength() > 0,
                    "EOM[" + i + "] headersByteLength should be positive. Debug:\n" + debug);
            }

            // The raw captured response bytes themselves must be exactly the two responses,
            // concatenated in order, with nothing dropped or duplicated.
            var combinedWrites = new SequenceInputStream(
                Collections.enumeration(
                    allObservations.stream()
                        .filter(TrafficObservation::hasWrite)
                        .map(to -> new ByteArrayInputStream(to.getWrite().getData().toByteArray()))
                        .collect(Collectors.toList())
                )
            );
            byte[] expectedWrites = (HEAD_RESPONSE + GET_RESPONSE).getBytes(StandardCharsets.UTF_8);
            Assertions.assertArrayEquals(expectedWrites, combinedWrites.readAllBytes(),
                "Combined Write observations should contain both responses' bytes, in order, uncorrupted. "
                + "Debug:\n" + debug);
        }
    }
}
