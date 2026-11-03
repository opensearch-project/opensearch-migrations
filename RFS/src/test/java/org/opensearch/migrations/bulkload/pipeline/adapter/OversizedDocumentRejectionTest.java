package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.CapturingFailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code maxDocumentSizeBytes}: oversized documents are recorded, never sent, and the cursor moves past them. */
class OversizedDocumentRejectionTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final long LIMIT = 40;

    private final List<byte[]> requests = new CopyOnWriteArrayList<>();
    private final CapturingFailedDocumentStreamSink stream = new CapturingFailedDocumentStreamSink();
    private final IRfsContexts.IRequestContext context = mock(IRfsContexts.IRequestContext.class);
    private OpenSearchClient client;

    @BeforeEach
    void setUp() {
        var transport = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(transport.getConnectionContext()).thenReturn(connection);
        when(transport.postAsyncBytes(any(), any(), any(), any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(1));
            return Mono.just(new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}"));
        });
        client = new OpenSearchClient_OS_2_11(transport, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 100, 10_000) {
            @Override
            protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                return Retry.max(0);
            }
        };
        client.setMaxDocumentSizeBytes(LIMIT);
        client.setFailedDocumentStreamContext(stream, "session", "worker");
    }

    private OpenSearchDocumentSink sink(IJsonTransformer transformer) {
        return new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer, false,
            DocumentExceptionAllowlist.empty(), () -> context);
    }

    /** A document whose source is exactly {@code bytes} long. */
    private static Document doc(String id, int bytes) {
        var prefix = "{\"v\":\"";
        var body = prefix + "x".repeat(bytes - prefix.length() - 2) + "\"}";
        return new Document(id, body.getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(), Map.of());
    }

    private List<String> sentIds() throws Exception {
        var ids = new ArrayList<String>();
        for (var request : requests) {
            var lines = new String(request, StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i += 2) {
                ids.add(MAPPER.readTree(lines[i]).path("index").path("_id").asText());
            }
        }
        return ids;
    }

    @Test
    void documentBodyLimitExcludesActionMetadataAndRejectsOnlyBodiesAboveIt() throws Exception {
        var result = sink(null).writeBatch("idx",
            List.of(doc("below", (int) LIMIT - 1), doc("at", (int) LIMIT), doc("above", (int) LIMIT + 1))).block();

        // The action metadata makes every complete NDJSON operation larger than LIMIT.
        assertEquals(List.of("below", "at"), sentIds());
        assertEquals(3, result.docsInBatch());
        assertThat(stream.getRecords().stream().map(FailedDocumentStreamRecord::getDocumentId).toList(),
            contains("above"));
        var rec = stream.getRecords().get(0);
        assertEquals(FailureClass.NON_RETRYABLE, rec.getFailureClass());
        assertEquals(OpenSearchClient.DOCUMENT_TOO_LARGE, rec.getFailureType());
        assertEquals(LIMIT + 1, rec.getResponseItem().path("size").asLong());
        assertEquals(LIMIT, rec.getResponseItem().path("limit").asLong());
        assertEquals(OpenSearchClient.MEASURED_REQUEST_BODY, rec.getResponseItem().path("measured").asText());
        verify(context).addDocumentsRejectedForSize(1);
        verify(context).addBulkDocumentsFailed(FailureClass.NON_RETRYABLE.name(), 1);
        assertEquals(new MigrationMetrics.Failures(1, 0), result.failures());
    }

    @Test
    void aBatchOfOnlyOversizedDocumentsSendsNothingButStillCompletes() {
        var result = sink(null).writeBatch("idx", List.of(doc("a", 100), doc("b", 100))).block();

        assertThat(requests, empty());
        assertEquals(2, result.docsInBatch());
        assertEquals(2, stream.getRecords().size());
        assertEquals(new MigrationMetrics.Failures(2, 0), result.failures());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSourceDocumentAboveTheLimitCanBeTransformedBelowIt() throws Exception {
        IJsonTransformer shrink = input -> {
            for (var op : (List<Map<String, Object>>) input) {
                op.put("document", Map.of("v", "small"));
            }
            return input;
        };

        var result = sink(shrink).writeBatch("idx", List.of(doc("big", 100))).block();

        assertEquals(List.of("big"), sentIds());
        assertThat(stream.getRecords(), empty());
        assertEquals(new MigrationMetrics.Failures(0, 0), result.failures());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSourceDocumentAboveTheLimitCanBeSplitIntoSmallerDocuments() throws Exception {
        IJsonTransformer split = input -> {
            var original = ((List<Map<String, Object>>) input).get(0);
            var first = new HashMap<>(original);
            var firstOperation = new HashMap<>((Map<String, Object>) original.get("operation"));
            firstOperation.put("_id", "part-1");
            first.put("operation", firstOperation);
            first.put("document", Map.of("part", 1));

            var second = new HashMap<>(original);
            var secondOperation = new HashMap<>((Map<String, Object>) original.get("operation"));
            secondOperation.put("_id", "part-2");
            second.put("operation", secondOperation);
            second.put("document", Map.of("part", 2));
            return List.of(first, second);
        };

        var result = sink(split).writeBatch("idx", List.of(doc("big", 100))).block();

        assertEquals(List.of("part-1", "part-2"), sentIds());
        assertThat(stream.getRecords(), empty());
        assertEquals(1, result.docsInBatch());
        assertEquals(2, result.target().upserts());
        assertEquals(new MigrationMetrics.Failures(0, 0), result.failures());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDocumentThatATransformationEnlargesPastTheLimitIsRejectedOnItsDocumentBody() throws Exception {
        IJsonTransformer enlarge = input -> {
            for (var op : (List<Map<String, Object>>) input) {
                ((Map<String, Object>) op.get("document")).put("added", "y".repeat(100));
            }
            return input;
        };

        var result = sink(enlarge).writeBatch("idx", List.of(doc("small", 20), doc("grows", 20))).block();

        assertThat(requests, empty());
        assertEquals(2, stream.getRecords().size());
        assertEquals(new MigrationMetrics.Failures(2, 0), result.failures());
        assertEquals(OpenSearchClient.MEASURED_REQUEST_BODY,
            stream.getRecords().get(0).getResponseItem().path("measured").asText());
    }

    @Test
    void theCursorAdvancesPastRejectedDocuments() {
        var partition = new EsShardPartition("snapshot", "idx", 0);
        var source = mock(DocumentSource.class);
        when(source.readDocuments(partition, 0))
            .thenReturn(Flux.just(doc("a", 20), doc("big", 100), doc("c", 20), doc("big2", 100)));

        var cursors = new DocumentMigrationPipeline(source, sink(null), 2, 10_000)
            .migratePartition(partition, "idx", 0).collectList().block(Duration.ofSeconds(10));

        assertEquals(4, cursors.get(cursors.size() - 1).nextPosition());
        assertEquals(2, stream.getRecords().size());
        assertEquals(new MigrationMetrics.Failures(2, 0), cursors.get(cursors.size() - 1).metrics().failures());
    }

    @Test
    void withoutAStreamOversizedDocumentsAreStillSkippedAndTheLossIsLoggedAsAnError() {
        client.setFailedDocumentStreamContext(null, "session", "worker");
        try (var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            sink(null).writeBatch("idx", List.of(doc("big-doc-id", 100))).block();

            assertThat(requests, empty());
            assertThat(logs.getLogEvents(), hasItem(containsString("is not recorded anywhere")));
            assertThat(logs.getLogEvents(), not(hasItem(containsString("xxxx"))));
        }
        verify(context).addDocumentsRejectedForSize(1);
    }

    @Test
    void noLimitMeansNothingIsRejected() throws Exception {
        client.setMaxDocumentSizeBytes(0);

        sink(null).writeBatch("idx", List.of(doc("big", 1000))).block();

        assertEquals(List.of("big"), sentIds());
        verify(context, never()).addDocumentsRejectedForSize(any(Long.class));
    }
}
