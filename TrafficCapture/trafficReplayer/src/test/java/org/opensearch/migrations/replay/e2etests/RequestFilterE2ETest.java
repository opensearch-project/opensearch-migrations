package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.RequestFilteredException;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


@Tag("longTest")
class RequestFilterE2ETest extends FullTrafficReplayerTest {

    @Test
    void rejectedRequestSkipsTargetAndStillCommitsItsKafkaRecord() throws Exception {
        var targetRequests = new AtomicInteger();
        try (var server = SimpleNettyHttpServer.makeServer(false, request -> {
            targetRequests.incrementAndGet();
            return new SimpleHttpResponse(
                Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                "OK".getBytes(StandardCharsets.UTF_8),
                "OK",
                200
            );
        })) {
            var result = replayOne(
                server.localhostEndpoint(),
                requestResponseAndClose("GET /filtered HTTP/1.1\r\nHost: source\r\n\r\n"),
                () -> input -> {
                    throw new RequestFilteredException("rejected by test filter");
                },
                null
            );

            Assertions.assertEquals(0, targetRequests.get());
            Assertions.assertEquals(1, result.tuples().size());
            Assertions.assertEquals(
                1L,
                result.committedOffsets().get(TOPIC_PARTITION).offset()
            );
            Assertions.assertTrue(result.fatalFailures().isEmpty());
        }
    }
}
