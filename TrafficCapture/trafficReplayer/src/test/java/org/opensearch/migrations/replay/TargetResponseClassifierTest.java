package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TargetResponseClassifierTest {
    private static final String BULK_REQUEST =
        "POST /_bulk HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
    private static final String REGULAR_REQUEST =
        "GET /missing HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";

    @Test
    void successfulBulkResponseIsARealSuccess() {
        try (var fixture = fixture(ExceptionTypeAllowlist.empty(), bulkResponse(false))) {
            assertInstanceOf(TargetOutcome.Succeeded.class, fixture.classify());
        }
    }

    @Test
    void nonRetryableFailureIsNotImplicitlyCommitEligible() {
        try (var fixture = fixture(
            ExceptionTypeAllowlist.empty(),
            bulkResponse(true, "version_conflict_engine_exception")
        )) {
            assertInstanceOf(TargetOutcome.Failed.class, fixture.classify());
        }
    }

    @Test
    void onlyFullyAllowlistedTerminalFailuresBecomeClassifiedSkips() {
        var allowlist = new ExceptionTypeAllowlist(Set.of("version_conflict_engine_exception"));
        try (var fixture = fixture(
            allowlist,
            bulkResponse(true, "version_conflict_engine_exception")
        )) {
            assertInstanceOf(TargetOutcome.ClassifiedSkip.class, fixture.classify());
        }
        try (var fixture = fixture(
            allowlist,
            bulkResponse(
                true,
                "version_conflict_engine_exception",
                "mapper_parsing_exception"
            )
        )) {
            assertInstanceOf(TargetOutcome.Failed.class, fixture.classify());
        }
    }

    @Test
    void retryableOrMalformedTerminalResponsesRemainFailures() {
        try (var fixture = fixture(
            new ExceptionTypeAllowlist(Set.of("unavailable_shards_exception")),
            bulkResponse(true, "unavailable_shards_exception")
        )) {
            assertInstanceOf(TargetOutcome.Failed.class, fixture.classify());
        }
        try (var fixture = fixture(ExceptionTypeAllowlist.empty(), httpResponse(200, "not-json"))) {
            assertInstanceOf(TargetOutcome.Failed.class, fixture.classify());
        }
    }

    @Test
    void sourceAndTargetNonSuccessResponsesAreASuccessfulReplay() {
        try (var fixture = fixture(
            ExceptionTypeAllowlist.empty(),
            REGULAR_REQUEST,
            httpResponse(405, "source rejected"),
            httpResponse(400, "target rejected")
        )) {
            assertInstanceOf(TargetOutcome.Succeeded.class, fixture.classify());
        }
    }

    @Test
    void targetNonSuccessAfterSourceSuccessRemainsAFailure() {
        try (var fixture = fixture(
            ExceptionTypeAllowlist.empty(),
            REGULAR_REQUEST,
            httpResponse(200, "source success"),
            httpResponse(404, "target missing")
        )) {
            assertInstanceOf(TargetOutcome.Failed.class, fixture.classify());
        }
    }

    private static Fixture fixture(ExceptionTypeAllowlist allowlist, String response) {
        return fixture(allowlist, BULK_REQUEST, httpResponse(200, "source success"), response);
    }

    private static Fixture fixture(
        ExceptionTypeAllowlist allowlist,
        String request,
        String sourceResponse,
        String targetResponse
    ) {
        var requestPacket = Unpooled.wrappedBuffer(request.getBytes(StandardCharsets.UTF_8));
        var requestPackets = new ByteBufList(requestPacket);
        requestPacket.release();
        var responseBytes = targetResponse.getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(
                    Stream.of(Unpooled.wrappedBuffer(responseBytes)),
                    0
                )
            )
            .addResponsePacket(responseBytes)
            .build();
        var summary = new TransformedTargetRequestAndResponseList(
            requestPackets,
            HttpRequestTransformationStatus.completed(),
            aggregatedResponse
        );
        return new Fixture(
            new TargetResponseClassifier(new BulkItemErrorClassifier(), allowlist),
            summary,
            sourcePair(sourceResponse)
        );
    }

    private static IRequestResponsePacketPair sourcePair(String responseBytes) {
        var response = new HttpMessageAndTimestamp.Response(Instant.EPOCH);
        response.add(responseBytes.getBytes(StandardCharsets.UTF_8));
        return new IRequestResponsePacketPair() {
            @Override
            public HttpMessageAndTimestamp getRequestData() {
                return null;
            }

            @Override
            public HttpMessageAndTimestamp getResponseData() {
                return response;
            }
        };
    }

    private static String bulkResponse(boolean errors, String... errorTypes) {
        var items = new StringBuilder();
        for (int i = 0; i < errorTypes.length; ++i) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"index\":{\"status\":400,\"error\":{\"type\":\"")
                .append(errorTypes[i])
                .append("\"}}}");
        }
        var body = "{\"errors\":" + errors + ",\"items\":[" + items + "]}";
        return httpResponse(200, body);
    }

    private static String httpResponse(int status, String body) {
        return "HTTP/1.1 "
            + status
            + " Result\r\nContent-Length: "
            + body.getBytes(StandardCharsets.UTF_8).length
            + "\r\nContent-Type: application/json\r\n\r\n"
            + body;
    }

    private record Fixture(
        TargetResponseClassifier classifier,
        TransformedTargetRequestAndResponseList summary,
        IRequestResponsePacketPair source
    ) implements AutoCloseable {
        TargetOutcome<TransformedTargetRequestAndResponseList> classify() {
            return classifier.classify(summary, source);
        }

        @Override
        public void close() {
            summary.requestPackets.release();
        }
    }
}
