package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.CapturingFailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives a whole partition through the pipeline, the real HTTP client, and a raw-socket server that misbehaves:
 * no response, a reset mid-request, an HTML 413, and a plain-text 429 storm. In every case the partition must
 * complete with its cursor past every document, and anything that couldn't be written must be in the failed
 * document stream.
 */
class UncleanBulkResponsesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(500);
    private static final int RETRIES = 8;
    private static final String HTML_413 =
        "<html><head><title>413 Request Entity Too Large</title></head><body><center>nginx</center></body></html>";

    private static ScriptedHttpServer.Action created(String body) {
        var ids = idsIn(body);
        return new ScriptedHttpServer.Respond(200, "application/json", BulkRequestGenerator.bulkItemResponse(false,
            ids.stream().map(BulkRequestGenerator::itemEntry).toList()));
    }

    private static List<String> idsIn(String body) {
        var lines = body.split("\n");
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < lines.length; i += 2) {
            try {
                ids.add(MAPPER.readTree(lines[i]).path("index").path("_id").asText());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return ids;
    }

    private record Outcome(List<ProgressCursor> cursors, List<FailedDocumentStreamRecord> records,
                           List<String> requestBodies) {
        long finalPosition() {
            return cursors.get(cursors.size() - 1).nextPosition();
        }

        List<String> recordIds() {
            return records.stream().map(FailedDocumentStreamRecord::getDocumentId).toList();
        }
    }

    private static Outcome migrate(int docsPerRequest, BiFunction<Integer, String, ScriptedHttpServer.Action> script,
                                   String... ids) throws Exception {
        try (var server = new ScriptedHttpServer(script)) {
            var connection = ConnectionContextTestParams.builder().host(server.uri()).build().toConnectionContext();
            var restClient = new RestClient(connection, HttpClient.create(), RESPONSE_TIMEOUT) {};
            var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(),
                Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, docsPerRequest, 10_000_000) {
                @Override
                protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                    return Retry.fixedDelay(RETRIES, Duration.ofMillis(10));
                }
            };
            var stream = new CapturingFailedDocumentStreamSink();
            client.setFailedDocumentStreamContext(stream, "session", "worker");
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);

            var partition = new EsShardPartition("snapshot", "idx", 0);
            var source = mock(DocumentSource.class);
            var docs = new java.util.ArrayList<Document>();
            for (int i = 0; i < ids.length; i++) {
                docs.add(new Document(ids[i], ("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8),
                    Document.Operation.UPSERT, Map.of(), Map.of()));
            }
            when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(docs));

            var cursors = new DocumentMigrationPipeline(source, sink, 100, 10_000_000)
                .migratePartition(partition, "idx", 0)
                .collectList()
                .block(Duration.ofMinutes(2));
            return new Outcome(cursors, stream.getRecords(), server.requestBodies());
        }
    }

    @Test
    void aDocumentThatNeverGetsAResponseIsIsolatedAndRecordedAndThePartitionCompletes() throws Exception {
        var outcome = migrate(100, (n, body) -> body != null && body.contains("\"slow\"")
            ? new ScriptedHttpServer.Hang()
            : (body == null ? new ScriptedHttpServer.ReadBody() : created(body)),
            "a", "slow", "c");

        assertEquals(3, outcome.finalPosition());
        assertThat(outcome.recordIds(), contains("slow"));
        var rec = outcome.records().get(0);
        assertEquals(FailureClass.RETRYABLE_EXHAUSTED, rec.getFailureClass());
        assertEquals("no_response", rec.getFailureType());
        var slowAlone = outcome.requestBodies().stream().filter(b -> idsIn(b).equals(List.of("slow"))).count();
        assertEquals((OpenSearchClient.MAX_NO_RESPONSE_RETRIES + 1), slowAlone);
    }

    @Test
    void aConnectionResetMidRequestIsRetriedUntilTheTargetAnswersAndNeverRecorded() throws Exception {
        // One document per request: the first is accepted, then more resets than any give-up allows, then the target
        // recovers. A reset says the target, not the document, is in trouble, so none of them counts.
        int resets = RETRIES - 1;   // more than MAX_NO_RESPONSE_RETRIES, within this test's retry schedule
        var outcome = migrate(1, (n, body) -> n == 1 || n > 1 + resets
            ? (body == null ? new ScriptedHttpServer.ReadBody() : created(body))
            : new ScriptedHttpServer.ResetMidRequest(),
            "a", "reset");

        assertEquals(2, outcome.finalPosition());
        assertThat(outcome.recordIds(), empty());
        assertThat(outcome.requestBodies().size(), greaterThanOrEqualTo(2));
    }

    @Test
    void anHtml413IsolatesTheOversizedDocumentWithoutRetryingAndThePartitionCompletes() throws Exception {
        var outcome = migrate(100, (n, body) -> body == null
            ? new ScriptedHttpServer.ReadBody()
            : body.contains("\"big\"") ? new ScriptedHttpServer.Respond(413, "text/html", HTML_413) : created(body),
            "a", "b", "big", "d");

        assertEquals(4, outcome.finalPosition());
        assertThat(outcome.recordIds(), contains("big"));
        var rec = outcome.records().get(0);
        assertEquals(FailureClass.NON_RETRYABLE, rec.getFailureClass());
        assertEquals("http_413", rec.getFailureType());
        // [a,b,big,d] → [a,b] + [big,d] → [big] + [d]; [big] is sent exactly once.
        assertThat(outcome.requestBodies(), hasSize(5));
        assertEquals(1, outcome.requestBodies().stream().filter(b -> idsIn(b).equals(List.of("big"))).count());
    }

    @Test
    void aPlainText429StormThenSuccessRetriesEverythingAndRecordsNothing() throws Exception {
        var outcome = migrate(100, (n, body) -> body == null
            ? new ScriptedHttpServer.ReadBody()
            : n <= 3 ? new ScriptedHttpServer.Respond(429, "text/plain", "429 Too Many Requests") : created(body),
            "a", "b", "c");

        assertEquals(3, outcome.finalPosition());
        assertThat(outcome.records(), empty());
        assertThat(outcome.requestBodies(), hasSize(4));
        assertThat(outcome.requestBodies(), everyItem(is(outcome.requestBodies().get(0))));
        assertEquals(3, outcome.cursors().get(0).metrics().requests().retries());
    }
}
