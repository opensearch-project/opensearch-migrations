package org.opensearch.migrations.replay.http.retries;

import io.netty.buffer.Unpooled;
import lombok.Getter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.util.TextTrackedFuture;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
class OpenSearchDefaultRetryTest {

    private static final String GET_REQUEST =
        "GET / HTTP/1.1\r\n" +
            "User-Agent: Unit Test\r\n" +
            "Host: localhost\r\n\r\n";

    private static String makeResponse(int statusCode) {
        return "HTTP/1.1 " + statusCode + " OK\r\n" +
            "Content-Length: 2\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            "hi\r\n";

    }

    @Getter
    private static class TestRequestResponsePair implements IRequestResponsePacketPair {
        HttpMessageAndTimestamp responseData;

        @Override public HttpMessageAndTimestamp getRequestData() { throw new IllegalStateException(); }

        public TestRequestResponsePair(byte[] bytes) {
            responseData = new HttpMessageAndTimestamp(Instant.now());
            responseData.add(bytes);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
        "200, 200, DONE",
        "200, 404, RETRY",
        "404, 200, DONE",
        "200, 401, DONE",
        "200, 403, DONE"
    })
    public void testStatusCodeMatches(int sourceStatusCode, int targetStatusCode,
                                      RequestSenderOrchestrator.RetryDirective expectedDirective)
        throws Exception
    {
        var retryChecker = new OpenSearchDefaultRetry();
        var sourceBytes = makeResponse(sourceStatusCode).getBytes(StandardCharsets.UTF_8);
        var targetBytes = makeResponse(targetStatusCode).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.apply(Unpooled.wrappedBuffer(GET_REQUEST.getBytes(StandardCharsets.UTF_8)),
            List.of(),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new TestRequestResponsePair(sourceBytes), () -> "test future"));
        Assertions.assertEquals(expectedDirective, determination.get());
    }
}