package org.opensearch.migrations.trafficcapture.netty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the capture proxy correctly handles HTTP 100 Continue exchanges.
 *
 * The HTTP/1.1 100 Continue protocol allows a client to send headers (with
 * Expect: 100-continue) and wait for the server to acknowledge before sending
 * the request body. The interim 100 Continue response is a flow-control signal,
 * not the final response to the request.
 *
 * Wire sequence:
 *   Client -> Server: POST / HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: N\r\n\r\n
 *   Server -> Client: HTTP/1.1 100 Continue\r\n\r\n
 *   Client -> Server: <body bytes>
 *   Server -> Client: HTTP/1.1 200 OK\r\n... (the real response)
 *
 * The captured TrafficStream must produce a coherent request/response pair.
 * If the interim 100 Continue is treated as a real response, the replayer's
 * state machine gets confused and either silently drops it or breaks downstream
 * accumulation.
 */
@Slf4j
@WrapWithNettyLeakDetection
public class HundredContinueCaptureTest {

    private static final String REQUEST_HEADERS_WITH_EXPECT =
        "POST /test-index/_doc HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "User-Agent: test-client\r\n"
        + "Accept: application/json\r\n"
        + "Content-Type: application/json\r\n"
        + "Content-Length: 26\r\n"
        + "Expect: 100-continue\r\n"
        + "\r\n";

    private static final String REQUEST_BODY = "{\"field\":\"value\",\"n\":42}\r\n";

    private static final String INTERIM_100_CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n";

    /**
     * A final 4xx/5xx response that the server may send INSTEAD of 100 Continue
     * to reject the request based on its headers (without reading the body).
     * Common in real proxies: 417 Expectation Failed, 413 Payload Too Large,
     * 401 Unauthorized.
     */
    private static final String REJECTION_RESPONSE_417 =
        "HTTP/1.1 417 Expectation Failed\r\n"
        + "Content-Type: text/plain\r\n"
        + "Content-Length: 19\r\n"
        + "Connection: close\r\n"
        + "\r\n"
        + "expectation rejected";

    private static final String REJECTION_RESPONSE_413 =
        "HTTP/1.1 413 Payload Too Large\r\n"
        + "Content-Type: text/plain\r\n"
        + "Content-Length: 13\r\n"
        + "Connection: close\r\n"
        + "\r\n"
        + "payload large";

    private static final String FINAL_RESPONSE =
        "HTTP/1.1 200 OK\r\n"
        + "Content-Type: application/json\r\n"
        + "Content-Length: 17\r\n"
        + "\r\n"
        + "{\"result\":\"ok\"}\r\n";

    private static final String SUBSEQUENT_REQUEST =
        "GET /healthcheck HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Accept: */*\r\n"
        + "\r\n";

    private static final String SUBSEQUENT_RESPONSE =
        "HTTP/1.1 200 OK\r\n"
        + "Content-Length: 2\r\n"
        + "\r\n"
        + "OK";

    /**
     * Helper that creates a capture handler, drives the channel through a
     * 100-Continue exchange, and returns all TrafficObservations from all
     * flushed traffic streams.
     *
     * The 100 Continue interim response is sent BEFORE the body arrives —
     * which means the proxy sees a Write while still parsing the request.
     */
    private static List<TrafficObservation> driveExchangeAndCapture(
        boolean splitInputs,
        Runnable extraExchange
    ) throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new MultiFlushStreamManager();
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
                if (splitInputs) {
                    // Headers in one TCP read
                    channel.writeInbound(Unpooled.wrappedBuffer(
                        REQUEST_HEADERS_WITH_EXPECT.getBytes(StandardCharsets.UTF_8)));
                    // Server sends 100 Continue while channel is still parsing
                    channel.writeOutbound(Unpooled.wrappedBuffer(
                        INTERIM_100_CONTINUE.getBytes(StandardCharsets.UTF_8)));
                    // Client sends the body
                    channel.writeInbound(Unpooled.wrappedBuffer(
                        REQUEST_BODY.getBytes(StandardCharsets.UTF_8)));
                    // Server sends the final response
                    channel.writeOutbound(Unpooled.wrappedBuffer(
                        FINAL_RESPONSE.getBytes(StandardCharsets.UTF_8)));
                } else {
                    // Headers + body in one packet
                    channel.writeInbound(Unpooled.wrappedBuffer(
                        (REQUEST_HEADERS_WITH_EXPECT + REQUEST_BODY).getBytes(StandardCharsets.UTF_8)));
                    // Server sends 100 Continue and the final response
                    channel.writeOutbound(Unpooled.wrappedBuffer(
                        INTERIM_100_CONTINUE.getBytes(StandardCharsets.UTF_8)));
                    channel.writeOutbound(Unpooled.wrappedBuffer(
                        FINAL_RESPONSE.getBytes(StandardCharsets.UTF_8)));
                }

                if (extraExchange != null) {
                    extraExchange.run();
                }
            } catch (Exception e) {
                log.warn("Exchange threw", e);
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

    /**
     * Format the observation list for failure messages.
     */
    private static String describeObservations(List<TrafficObservation> observations) {
        var sb = new StringBuilder();
        sb.append("observations (").append(observations.size()).append("):\n");
        for (int i = 0; i < observations.size(); i++) {
            var obs = observations.get(i);
            if (obs.hasRead()) {
                var data = obs.getRead().getData().toByteArray();
                sb.append("  [").append(i).append("] Read(").append(data.length).append("b) ");
                sb.append(new String(data, StandardCharsets.UTF_8)
                    .replace("\r", "\\r").replace("\n", "\\n"));
                sb.append("\n");
            } else if (obs.hasWrite()) {
                var data = obs.getWrite().getData().toByteArray();
                sb.append("  [").append(i).append("] Write(").append(data.length).append("b) ");
                sb.append(new String(data, StandardCharsets.UTF_8)
                    .replace("\r", "\\r").replace("\n", "\\n"));
                sb.append("\n");
            } else if (obs.hasInterimResponse()) {
                var data = obs.getInterimResponse().getData().toByteArray();
                sb.append("  [").append(i).append("] InterimResponse(").append(data.length).append("b) ");
                sb.append(new String(data, StandardCharsets.UTF_8)
                    .replace("\r", "\\r").replace("\n", "\\n"));
                sb.append("\n");
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

    /**
     * Verifies that a 100-Continue exchange does NOT produce a malformed
     * traffic stream where the interim 100 response appears between the
     * request headers and the request body, breaking the read/EOM/write
     * ordering invariant.
     *
     * The captured stream must have the request fully accumulated in Read
     * observations BEFORE the EOM, with the interim 100 Continue either
     * filtered out or placed after the EOM (as the start of the response).
     */
    @Test
    public void testHundredContinueDoesNotBreakRequestResponseOrdering() throws IOException {
        var observations = driveExchangeAndCapture(true, null);
        var debug = describeObservations(observations);

        // Find the EOM index
        int eomIdx = -1;
        for (int i = 0; i < observations.size(); i++) {
            if (observations.get(i).hasEndOfMessageIndicator()) {
                eomIdx = i;
                break;
            }
        }
        Assertions.assertTrue(eomIdx >= 0,
            "Expected EOM observation. " + debug);

        // All Read observations should appear BEFORE the EOM.
        // No Read should appear AFTER the EOM (that would mean the body
        // arrived after the interim response was treated as the final response).
        for (int i = eomIdx + 1; i < observations.size(); i++) {
            Assertions.assertFalse(observations.get(i).hasRead(),
                "Read observation found AFTER EOM at index " + i
                + ". This means the request body was not properly accumulated "
                + "before the response. " + debug);
        }

        // The Read observations combined must contain the full request
        // (headers + body), not just the headers.
        var combinedReads = observations.stream()
            .filter(TrafficObservation::hasRead)
            .map(o -> o.getRead().getData().toByteArray())
            .collect(Collectors.toList());
        var combinedReadsBytes = new SequenceInputStream(Collections.enumeration(
            combinedReads.stream().map(ByteArrayInputStream::new).collect(Collectors.toList())
        )).readAllBytes();
        var combinedReadsStr = new String(combinedReadsBytes, StandardCharsets.UTF_8);

        Assertions.assertTrue(combinedReadsStr.contains("Expect: 100-continue"),
            "Combined reads should contain the original Expect header. " + debug);
        Assertions.assertTrue(combinedReadsStr.contains(REQUEST_BODY),
            "Combined reads should contain the request body that was sent after "
            + "the interim 100 Continue response. " + debug);
    }

    /**
     * Verifies that the interim 100 Continue response, when present in the
     * stream, does NOT appear before the EOM (which would break ordering)
     * AND does not corrupt the final response data.
     *
     * The "right" handling is one of:
     *   (a) Drop the 100 Continue write entirely (it's not a real response).
     *   (b) Defer it until after the EOM is emitted.
     */
    @Test
    public void testHundredContinueWritePlacedCorrectly() throws IOException {
        var observations = driveExchangeAndCapture(true, null);
        var debug = describeObservations(observations);

        int eomIdx = -1;
        for (int i = 0; i < observations.size(); i++) {
            if (observations.get(i).hasEndOfMessageIndicator()) {
                eomIdx = i;
                break;
            }
        }
        Assertions.assertTrue(eomIdx >= 0,
            "Expected EOM observation. " + debug);

        // No Write observation should appear BEFORE the EOM. If the proxy
        // emits the 100 Continue as a Write before the EOM, that's the bug
        // we're documenting/fixing.
        for (int i = 0; i < eomIdx; i++) {
            Assertions.assertFalse(observations.get(i).hasWrite(),
                "Write observation found BEFORE EOM at index " + i
                + ". The interim 100 Continue response should not appear "
                + "before the request EOM. " + debug);
        }

        // The Write observations after the EOM must contain the FINAL
        // response, not the interim 100 Continue (or include both, but the
        // final response must be present).
        var combinedWrites = observations.stream()
            .filter(TrafficObservation::hasWrite)
            .map(o -> o.getWrite().getData().toByteArray())
            .collect(Collectors.toList());
        var combinedWritesBytes = new SequenceInputStream(Collections.enumeration(
            combinedWrites.stream().map(ByteArrayInputStream::new).collect(Collectors.toList())
        )).readAllBytes();
        var combinedWritesStr = new String(combinedWritesBytes, StandardCharsets.UTF_8);

        Assertions.assertTrue(combinedWritesStr.contains("HTTP/1.1 200 OK"),
            "Combined writes must contain the final 200 OK response. " + debug);
    }

    /**
     * Verifies that the interim 100 Continue bytes are preserved as an
     * InterimResponse observation (not silently dropped). This ensures the
     * captured stream is byte-faithful for audit/analysis even though the
     * replayer's HTTP client will negotiate 100-Continue with the target
     * server independently.
     */
    @Test
    public void testHundredContinueIsCapturedAsInterimResponse() throws IOException {
        var observations = driveExchangeAndCapture(true, null);
        var debug = describeObservations(observations);

        // There must be at least one InterimResponse observation containing
        // the 100 Continue bytes.
        var interimResponses = observations.stream()
            .filter(TrafficObservation::hasInterimResponse)
            .collect(Collectors.toList());
        Assertions.assertFalse(interimResponses.isEmpty(),
            "Expected at least one InterimResponse observation for the "
            + "100 Continue. " + debug);

        var interimBytes = new SequenceInputStream(Collections.enumeration(
            interimResponses.stream()
                .map(o -> new ByteArrayInputStream(o.getInterimResponse().getData().toByteArray()))
                .collect(Collectors.toList())
        )).readAllBytes();
        var interimStr = new String(interimBytes, StandardCharsets.UTF_8);
        Assertions.assertTrue(interimStr.contains("HTTP/1.1 100 Continue"),
            "InterimResponse observation must contain the 100 Continue bytes. " + debug);

        // The InterimResponse must appear BEFORE the EOM (in its natural
        // position in the wire timeline).
        int eomIdx = -1;
        int interimIdx = -1;
        for (int i = 0; i < observations.size(); i++) {
            if (observations.get(i).hasEndOfMessageIndicator() && eomIdx < 0) {
                eomIdx = i;
            }
            if (observations.get(i).hasInterimResponse() && interimIdx < 0) {
                interimIdx = i;
            }
        }
        Assertions.assertTrue(interimIdx >= 0 && eomIdx >= 0,
            "Expected both InterimResponse and EOM. " + debug);
        Assertions.assertTrue(interimIdx < eomIdx,
            "InterimResponse should appear BEFORE the EOM "
            + "(it arrived during the request phase). interimIdx=" + interimIdx
            + ", eomIdx=" + eomIdx + ". " + debug);
    }

    /**
     * Verifies that after a 100-Continue exchange, a subsequent request on
     * the same connection is captured cleanly (the handler state did not
     * get corrupted by the interim response).
     */
    @Test
    public void testRequestAfter100ContinueIsCapturedCorrectly() throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new MultiFlushStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext, "n", "c", ctx -> offloader,
                    new RequestCapturePredicate(), x -> true)
            );

            try {
                // First exchange: 100-Continue
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_HEADERS_WITH_EXPECT.getBytes(StandardCharsets.UTF_8)));
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    INTERIM_100_CONTINUE.getBytes(StandardCharsets.UTF_8)));
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_BODY.getBytes(StandardCharsets.UTF_8)));
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    FINAL_RESPONSE.getBytes(StandardCharsets.UTF_8)));

                // Second exchange: simple GET
                channel.writeInbound(Unpooled.wrappedBuffer(
                    SUBSEQUENT_REQUEST.getBytes(StandardCharsets.UTF_8)));
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    SUBSEQUENT_RESPONSE.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.warn("Exchange threw", e);
            }
            channel.finishAndReleaseAll();
            channel.close();

            List<TrafficObservation> allObservations = new ArrayList<>();
            for (var buf : streamManager.flushedBuffers) {
                var trafficStream = TrafficStream.parseFrom(buf);
                allObservations.addAll(trafficStream.getSubStreamList());
            }

            var debug = describeObservations(allObservations);

            // Should have exactly 2 EOM markers (one per request)
            long eomCount = allObservations.stream()
                .filter(TrafficObservation::hasEndOfMessageIndicator).count();
            Assertions.assertEquals(2, eomCount,
                "Expected 2 EOM observations (one per request). " + debug);

            // All EOMs should have proper byte-length values
            allObservations.stream()
                .filter(TrafficObservation::hasEndOfMessageIndicator)
                .forEach(obs -> {
                    var eom = obs.getEndOfMessageIndicator();
                    Assertions.assertTrue(eom.getFirstLineByteLength() > 0,
                        "EOM firstLineByteLength should be positive: " + eom + ". " + debug);
                    Assertions.assertTrue(eom.getHeadersByteLength() > 0,
                        "EOM headersByteLength should be positive: " + eom + ". " + debug);
                });
        }
    }

    /**
     * Verifies that when a server REJECTS the expectation with a final 4xx/5xx
     * response (instead of sending 100 Continue), the proxy correctly captures
     * the rejection as the actual response — NOT as an interim 1xx.
     *
     * Wire sequence:
     *   Client -> Server: POST /...Expect: 100-continue\r\nContent-Length: N\r\n\r\n
     *   Server -> Client: HTTP/1.1 417 Expectation Failed\r\n...\r\n\r\n<body>
     *   (client may abort; no body bytes follow)
     *
     * The captured stream must put the 417 response on the Write side (not
     * InterimResponse), because it is the final response to this exchange.
     */
    @Test
    public void test417RejectionIsCapturedAsRealResponse() throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new MultiFlushStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext, "n", "c", ctx -> offloader,
                    new RequestCapturePredicate(), x -> true)
            );

            try {
                // Client sends headers expecting 100-continue
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_HEADERS_WITH_EXPECT.getBytes(StandardCharsets.UTF_8)));
                // Server rejects with 417 (instead of sending 100 Continue)
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    REJECTION_RESPONSE_417.getBytes(StandardCharsets.UTF_8)));
                // In real life, the client may still send the body or abort.
                // We test the case where it sends the body anyway.
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_BODY.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.warn("Exchange threw", e);
            }
            channel.finishAndReleaseAll();
            channel.close();

            List<TrafficObservation> allObservations = new ArrayList<>();
            for (var buf : streamManager.flushedBuffers) {
                var trafficStream = TrafficStream.parseFrom(buf);
                allObservations.addAll(trafficStream.getSubStreamList());
            }
            var debug = describeObservations(allObservations);

            // The 417 rejection MUST appear as a Write observation (final response),
            // NOT as an InterimResponse observation. It is the actual response to
            // this HTTP exchange, even though it arrived during the request phase.
            var writes = allObservations.stream()
                .filter(TrafficObservation::hasWrite)
                .map(o -> new String(o.getWrite().getData().toByteArray(), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
            var hasFinalResponseAsWrite = writes.stream()
                .anyMatch(w -> w.contains("HTTP/1.1 417 Expectation Failed"));
            Assertions.assertTrue(hasFinalResponseAsWrite,
                "417 rejection must be captured as a Write (final response), "
                + "not as InterimResponse. " + debug);

            // The 417 rejection must NOT appear in InterimResponse observations.
            var interimsContaining417 = allObservations.stream()
                .filter(TrafficObservation::hasInterimResponse)
                .map(o -> new String(o.getInterimResponse().getData().toByteArray(), StandardCharsets.UTF_8))
                .filter(s -> s.contains("417"))
                .count();
            Assertions.assertEquals(0, interimsContaining417,
                "417 rejection must not be captured as InterimResponse. " + debug);
        }
    }

    /**
     * Same as test417RejectionIsCapturedAsRealResponse but with a 413
     * Payload Too Large response. Different status, same flow:
     * a final non-1xx response sent during the request phase.
     */
    @Test
    public void test413RejectionIsCapturedAsRealResponse() throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new MultiFlushStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext, "n", "c", ctx -> offloader,
                    new RequestCapturePredicate(), x -> true)
            );

            try {
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_HEADERS_WITH_EXPECT.getBytes(StandardCharsets.UTF_8)));
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    REJECTION_RESPONSE_413.getBytes(StandardCharsets.UTF_8)));
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_BODY.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.warn("Exchange threw", e);
            }
            channel.finishAndReleaseAll();
            channel.close();

            List<TrafficObservation> allObservations = new ArrayList<>();
            for (var buf : streamManager.flushedBuffers) {
                var trafficStream = TrafficStream.parseFrom(buf);
                allObservations.addAll(trafficStream.getSubStreamList());
            }
            var debug = describeObservations(allObservations);

            var writes = allObservations.stream()
                .filter(TrafficObservation::hasWrite)
                .map(o -> new String(o.getWrite().getData().toByteArray(), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
            Assertions.assertTrue(writes.stream().anyMatch(w -> w.contains("HTTP/1.1 413 Payload Too Large")),
                "413 rejection must be captured as a Write (final response), "
                + "not as InterimResponse. " + debug);

            var interimsContaining413 = allObservations.stream()
                .filter(TrafficObservation::hasInterimResponse)
                .map(o -> new String(o.getInterimResponse().getData().toByteArray(), StandardCharsets.UTF_8))
                .filter(s -> s.contains("413"))
                .count();
            Assertions.assertEquals(0, interimsContaining413,
                "413 rejection must not be captured as InterimResponse. " + debug);
        }
    }

    /**
     * Verifies that when the server sends a 100 Continue followed by a 4xx
     * (an unusual but legal HTTP/1.1 sequence), the 100 is captured as
     * InterimResponse and the 4xx is captured as the real Write response.
     */
    @Test
    public void test100ContinueFollowedByRejectionAfterBody() throws IOException {
        try (var rootContext = new TestRootContext()) {
            var streamManager = new MultiFlushStreamManager();
            var offloader = new StreamChannelConnectionCaptureSerializer<>("Test", "c", streamManager);

            EmbeddedChannel channel = new EmbeddedChannel(
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext, "n", "c", ctx -> offloader,
                    new RequestCapturePredicate(), x -> true)
            );

            try {
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_HEADERS_WITH_EXPECT.getBytes(StandardCharsets.UTF_8)));
                // Server says go ahead
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    INTERIM_100_CONTINUE.getBytes(StandardCharsets.UTF_8)));
                // Client sends body
                channel.writeInbound(Unpooled.wrappedBuffer(
                    REQUEST_BODY.getBytes(StandardCharsets.UTF_8)));
                // Server then rejects the body content
                channel.writeOutbound(Unpooled.wrappedBuffer(
                    REJECTION_RESPONSE_413.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.warn("Exchange threw", e);
            }
            channel.finishAndReleaseAll();
            channel.close();

            List<TrafficObservation> allObservations = new ArrayList<>();
            for (var buf : streamManager.flushedBuffers) {
                var trafficStream = TrafficStream.parseFrom(buf);
                allObservations.addAll(trafficStream.getSubStreamList());
            }
            var debug = describeObservations(allObservations);

            // The 100 Continue must be captured as InterimResponse.
            var interims = allObservations.stream()
                .filter(TrafficObservation::hasInterimResponse)
                .map(o -> new String(o.getInterimResponse().getData().toByteArray(), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
            Assertions.assertTrue(interims.stream().anyMatch(s -> s.contains("100 Continue")),
                "100 Continue must appear as InterimResponse. " + debug);

            // The 413 must be the final Write.
            var writes = allObservations.stream()
                .filter(TrafficObservation::hasWrite)
                .map(o -> new String(o.getWrite().getData().toByteArray(), StandardCharsets.UTF_8))
                .collect(Collectors.toList());
            Assertions.assertTrue(writes.stream().anyMatch(s -> s.contains("HTTP/1.1 413")),
                "413 rejection must appear as Write (final response). " + debug);

            // The 413 must NOT be in InterimResponse.
            Assertions.assertEquals(0,
                interims.stream().filter(s -> s.contains("413")).count(),
                "413 must not be captured as InterimResponse. " + debug);
        }
    }

    /**
     * Stream manager that accumulates ALL flushed buffers (not just the last),
     * since ConditionallyReliableLoggingHttpHandler flushes between requests.
     */
    static class MultiFlushStreamManager
        extends org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager
        implements AutoCloseable {

        final List<java.nio.ByteBuffer> flushedBuffers = new ArrayList<>();
        final AtomicInteger flushCount = new AtomicInteger();

        @Override
        public void close() {}

        @Override
        public org.opensearch.migrations.trafficcapture.CodedOutputStreamAndByteBufferWrapper createStream() {
            return new org.opensearch.migrations.trafficcapture
                .CodedOutputStreamAndByteBufferWrapper(1024 * 1024);
        }

        @Override
        @lombok.SneakyThrows
        public java.util.concurrent.CompletableFuture<Object> kickoffCloseStream(
            org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder holder, int index) {
            var osh = (org.opensearch.migrations.trafficcapture
                .CodedOutputStreamAndByteBufferWrapper) holder;
            osh.getOutputStream().flush();
            flushedBuffers.add(osh.getByteBuffer().flip().asReadOnlyBuffer());
            return java.util.concurrent.CompletableFuture.completedFuture(flushCount.incrementAndGet());
        }
    }
}
