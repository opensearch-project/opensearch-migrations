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
 * Tests that the capture proxy correctly handles HTTP requests using
 * Transfer-Encoding: chunked, particularly when the chunk terminator
 * arrives in a separate TCP read from the main body.
 */
@Slf4j
@WrapWithNettyLeakDetection
public class ChunkedTransferEncodingCaptureTest {

    private static final String JSON_BODY = "{\"query\":{\"match_all\":{}},\"size\":10}";

    private static final String CHUNK_SIZE_HEX = Integer.toHexString(JSON_BODY.length());

    private static final String REQUEST_HEADERS =
        "POST /test-index/_search HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Transfer-Encoding: chunked\r\n"
        + "User-Agent: test-client\r\n"
        + "Accept: application/json\r\n"
        + "Content-Type: application/json\r\n"
        + "\r\n";

    private static final String CHUNKED_BODY =
        CHUNK_SIZE_HEX + "\r\n"
        + JSON_BODY + "\r\n";

    private static final String CHUNK_TERMINATOR = "0\r\n\r\n";

    private static final String FULL_CHUNKED_REQUEST =
        REQUEST_HEADERS + CHUNKED_BODY + CHUNK_TERMINATOR;

    private static final String SIMPLE_GET_REQUEST =
        "GET / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Accept: */*\r\n"
        + "\r\n";

    /**
     * A stream manager that accumulates all flushed buffers rather than
     * only keeping the last one. This is needed because the
     * ConditionallyReliableLoggingHttpHandler flushes between requests
     * (when blocking is enabled), resulting in multiple TrafficStream
     * protobuf messages.
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
            if (!(outputStreamHolder instanceof CodedOutputStreamAndByteBufferWrapper)) {
                throw new IllegalStateException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            var osh = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
            CodedOutputStream cos = osh.getOutputStream();
            cos.flush();
            flushedBuffers.add(osh.getByteBuffer().flip().asReadOnlyBuffer());
            return CompletableFuture.completedFuture(flushCount.incrementAndGet());
        }
    }

    /**
     * Helper that creates a channel, writes data via the provided writer, and
     * returns all TrafficObservation instances from all flushed streams.
     */
    private List<TrafficObservation> captureObservations(
        java.util.function.Consumer<EmbeddedChannel> channelWriter
    ) throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new AccumulatingStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext,
                    "n",
                    "c",
                    ctx -> offloader,
                    new RequestCapturePredicate(),
                    x -> true
                )
            );

            try {
                channelWriter.accept(channel);
            } catch (Exception e) {
                // Swallow exceptions from the channel writer so we can still
                // inspect the traffic stream observations
            }
            channel.finishAndReleaseAll();
            channel.close();

            Assertions.assertFalse(streamManager.flushedBuffers.isEmpty(),
                "Expected at least one flushed traffic stream");

            List<TrafficObservation> allObservations = new ArrayList<>();
            for (var buf : streamManager.flushedBuffers) {
                var trafficStream = TrafficStream.parseFrom(buf);
                allObservations.addAll(trafficStream.getSubStreamList());
            }
            return allObservations;
        }
    }

    private static String describeObservations(List<TrafficObservation> observations) {
        StringBuilder sb = new StringBuilder();
        sb.append("observation count: ").append(observations.size()).append("\n");
        for (int i = 0; i < observations.size(); i++) {
            var obs = observations.get(i);
            if (obs.hasRead()) {
                sb.append("  [").append(i).append("] Read(")
                    .append(obs.getRead().getData().size()).append("b)\n");
            } else if (obs.hasEndOfMessageIndicator()) {
                var eom = obs.getEndOfMessageIndicator();
                sb.append("  [").append(i).append("] EOM(firstLine=")
                    .append(eom.getFirstLineByteLength())
                    .append(", headers=").append(eom.getHeadersByteLength()).append(")\n");
            } else if (obs.hasConnectionException()) {
                sb.append("  [").append(i).append("] Exception: ")
                    .append(obs.getConnectionException().getMessage()).append("\n");
            } else {
                sb.append("  [").append(i).append("] ").append(obs.getCaptureCase()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Test a chunked request arriving in a single TCP read (all bytes at once).
     * This verifies that the EOM has proper firstLineByteLength and headersByteLength values.
     */
    @Test
    public void testChunkedRequestInSingleRead() throws IOException {
        byte[] fullBytes = FULL_CHUNKED_REQUEST.getBytes(StandardCharsets.UTF_8);

        var observations = captureObservations(channel ->
            channel.writeInbound(Unpooled.wrappedBuffer(fullBytes))
        );

        String debug = describeObservations(observations);

        // Find the EOM observation
        var eomObs = observations.stream()
            .filter(TrafficObservation::hasEndOfMessageIndicator)
            .findFirst();
        Assertions.assertTrue(eomObs.isPresent(),
            "Expected an EndOfMessageIndicator observation. Debug:\n" + debug);

        var eom = eomObs.get().getEndOfMessageIndicator();

        // The EOM must have proper values, NOT -1
        Assertions.assertTrue(eom.getFirstLineByteLength() > 0,
            "firstLineByteLength should be positive, was: " + eom.getFirstLineByteLength());
        Assertions.assertTrue(eom.getHeadersByteLength() > 0,
            "headersByteLength should be positive, was: " + eom.getHeadersByteLength());

        // No Read observation should appear AFTER the EOM
        boolean foundEom = false;
        for (var obs : observations) {
            if (obs.hasEndOfMessageIndicator()) {
                foundEom = true;
            } else if (foundEom && obs.hasRead()) {
                Assertions.fail("Found a Read observation after the EOM, which indicates the chunk "
                    + "terminator was emitted as a separate read. Read data size: "
                    + obs.getRead().getData().size() + " bytes. Debug:\n" + debug);
            }
        }

        // Verify all request bytes are captured in Read observations
        var combinedReads = new SequenceInputStream(
            Collections.enumeration(
                observations.stream()
                    .filter(TrafficObservation::hasRead)
                    .map(to -> new ByteArrayInputStream(to.getRead().getData().toByteArray()))
                    .collect(Collectors.toList())
            )
        );
        Assertions.assertArrayEquals(fullBytes, combinedReads.readAllBytes(),
            "Combined Read observations should contain all request bytes");
    }

    /**
     * Test a chunked request arriving in TWO TCP reads:
     * - First read: headers + chunked body data
     * - Second read: chunk terminator 0\r\n\r\n
     *
     * This tests the scenario where the chunk terminator arrives separately.
     */
    @Test
    public void testChunkedRequestSplitAtTerminator() throws IOException {
        byte[] firstRead = (REQUEST_HEADERS + CHUNKED_BODY).getBytes(StandardCharsets.UTF_8);
        byte[] secondRead = CHUNK_TERMINATOR.getBytes(StandardCharsets.UTF_8);
        byte[] fullBytes = FULL_CHUNKED_REQUEST.getBytes(StandardCharsets.UTF_8);

        var observations = captureObservations(channel -> {
            channel.writeInbound(Unpooled.wrappedBuffer(firstRead));
            channel.writeInbound(Unpooled.wrappedBuffer(secondRead));
        });

        String debug = describeObservations(observations);

        // Find the EOM observation
        var eomObs = observations.stream()
            .filter(TrafficObservation::hasEndOfMessageIndicator)
            .findFirst();
        Assertions.assertTrue(eomObs.isPresent(),
            "Expected an EndOfMessageIndicator observation. Debug:\n" + debug);

        var eom = eomObs.get().getEndOfMessageIndicator();

        // The EOM must have proper values, NOT -1
        Assertions.assertTrue(eom.getFirstLineByteLength() > 0,
            "firstLineByteLength should be positive, was: " + eom.getFirstLineByteLength());
        Assertions.assertTrue(eom.getHeadersByteLength() > 0,
            "headersByteLength should be positive, was: " + eom.getHeadersByteLength());

        // No Read observation should appear AFTER the EOM for the same request
        boolean foundEom = false;
        for (var obs : observations) {
            if (obs.hasEndOfMessageIndicator()) {
                foundEom = true;
            } else if (foundEom && obs.hasRead()) {
                Assertions.fail("Found a Read observation after the EOM, which indicates the chunk "
                    + "terminator was emitted as a separate read after EOM. Read data size: "
                    + obs.getRead().getData().size() + " bytes. Debug:\n" + debug);
            }
        }

        // Verify all request bytes are captured in Read observations
        var combinedReads = new SequenceInputStream(
            Collections.enumeration(
                observations.stream()
                    .filter(TrafficObservation::hasRead)
                    .map(to -> new ByteArrayInputStream(to.getRead().getData().toByteArray()))
                    .collect(Collectors.toList())
            )
        );
        Assertions.assertArrayEquals(fullBytes, combinedReads.readAllBytes(),
            "Combined Read observations should contain all request bytes including chunk terminator");
    }

    /**
     * Test that a subsequent request on the same connection after a chunked request
     * is captured correctly, verifying the handler state is not corrupted.
     */
    @Test
    public void testChunkedRequestFollowedByNormalRequest() throws IOException {
        byte[] chunkedBytes = FULL_CHUNKED_REQUEST.getBytes(StandardCharsets.UTF_8);
        byte[] getBytes = SIMPLE_GET_REQUEST.getBytes(StandardCharsets.UTF_8);

        var observations = captureObservations(channel -> {
            // Send chunked request in two parts (terminator arrives separately)
            byte[] firstRead = (REQUEST_HEADERS + CHUNKED_BODY).getBytes(StandardCharsets.UTF_8);
            byte[] secondRead = CHUNK_TERMINATOR.getBytes(StandardCharsets.UTF_8);
            channel.writeInbound(Unpooled.wrappedBuffer(firstRead));
            channel.writeInbound(Unpooled.wrappedBuffer(secondRead));
            // Send a normal GET request on the same connection
            channel.writeInbound(Unpooled.wrappedBuffer(getBytes));
        });

        String debug = describeObservations(observations);

        // Should have exactly 2 EOM indicators (one per request)
        long eomCount = observations.stream()
            .filter(TrafficObservation::hasEndOfMessageIndicator)
            .count();
        Assertions.assertEquals(2, eomCount,
            "Expected 2 EndOfMessageIndicator observations (one for chunked POST, one for GET). "
            + "Debug:\n" + debug);

        // Both EOMs should have valid firstLineByteLength and headersByteLength
        var eoms = observations.stream()
            .filter(TrafficObservation::hasEndOfMessageIndicator)
            .collect(Collectors.toList());

        for (int i = 0; i < eoms.size(); i++) {
            var eom = eoms.get(i).getEndOfMessageIndicator();
            Assertions.assertTrue(eom.getFirstLineByteLength() > 0,
                "EOM[" + i + "] firstLineByteLength should be positive, was: "
                + eom.getFirstLineByteLength());
            Assertions.assertTrue(eom.getHeadersByteLength() > 0,
                "EOM[" + i + "] headersByteLength should be positive, was: "
                + eom.getHeadersByteLength());
        }
    }
}
