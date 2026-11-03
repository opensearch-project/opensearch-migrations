package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

/**
 * Bulk requests wait {@link OpenSearchClient#setBulkResponseTimeout} for a response instead of the RestClient's
 * timeout, which other requests keep, and a bulk that times out is logged with how long it waited.
 */
class BulkResponseTimeoutTest {
    private static final String OK_BODY = BulkRequestGenerator.bulkItemResponse(false,
        List.of(BulkRequestGenerator.itemEntry("a")));

    private record Run(int requests, long elapsedMillis, List<String> warnings) {}

    private static Run sendOne(Duration clientTimeout, Duration bulkTimeout,
                               BiFunction<Integer, String, ScriptedHttpServer.Action> script) throws Exception {
        try (var server = new ScriptedHttpServer(script);
             var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            var connection = ConnectionContextTestParams.builder().host(server.uri()).build().toConnectionContext();
            var restClient = new RestClient(connection, HttpClient.create(), clientTimeout) {};
            var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(),
                Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 100, 10_000_000) {
                @Override
                protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                    return Retry.fixedDelay(1, Duration.ofMillis(10));
                }
            };
            client.setBulkResponseTimeout(bulkTimeout);
            // Only the bulk requests are under test, not the probe that asks whether the target answers.
            client.setTargetProbe(() -> reactor.core.publisher.Mono.just(true));
            var doc = new Document("a", "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(),
                Map.of());
            long start = System.nanoTime();
            try {
                client.sendBulkRequestRaw("idx", List.of(doc), null, false, DocumentExceptionAllowlist.empty())
                    .block(Duration.ofSeconds(30));
            } catch (RuntimeException e) {
                // Without a failed document stream an unanswered batch fails; the timing is what's under test.
            }
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            var warnings = new ArrayList<>(logs.getLogEvents());
            return new Run(server.requestBodies().size(), elapsed, warnings);
        }
    }

    private static ScriptedHttpServer.Action answerAfter(String body, Duration delay) {
        return body == null ? new ScriptedHttpServer.ReadBody()
            : new ScriptedHttpServer.RespondAfter(delay, new ScriptedHttpServer.Respond(200, "application/json", OK_BODY));
    }

    @Test
    void aSlowBulkResponseWithinTheBulkTimeoutIsNotCutOffByTheClientTimeout() throws Exception {
        var run = sendOne(Duration.ofMillis(200), Duration.ofSeconds(5),
            (n, body) -> answerAfter(body, Duration.ofMillis(800)));

        Assertions.assertEquals(1, run.requests(), "warnings: " + run.warnings());
    }

    @Test
    void aBulkGivesUpAtTheBulkTimeoutAndLogsHowLongItWaited() throws Exception {
        var run = sendOne(Duration.ofSeconds(20), Duration.ofMillis(300),
            (n, body) -> body == null ? new ScriptedHttpServer.ReadBody() : new ScriptedHttpServer.Hang());

        Assertions.assertEquals(2, run.requests());
        Assertions.assertTrue(run.elapsedMillis() < 10_000, "took " + run.elapsedMillis() + " ms");
        Assertions.assertTrue(run.warnings().stream().anyMatch(w -> w.contains("got no response after")
                && w.contains("(timed out at 300 ms;")),
            "warnings: " + run.warnings());
    }
}
