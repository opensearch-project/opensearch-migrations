package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.testfixtures.SupportedClusters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Checks against real clusters what {@link LargeDocumentSplitTest} checks against an in-memory target. */
@Tag("isolatedTest")
class LargeDocumentSplitEndToEndTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REQUEST_BYTES = 2048;
    private static final String ROUTING = "r1";
    // ~7 KB, with a nested object that itself exceeds one request, and numbers a careless rewrite would alter.
    private static final String LARGE = "{\"title\":\"" + "t".repeat(1200) + "\",\"price\":1.50,\"ratio\":0.1,"
        + "\"exact\":9007199254740993,\"tiny\":[1E-7,-0.0,2.5e3],"
        + "\"profile\":{\"bio\":\"" + "a".repeat(1500) + "\",\"geo\":{\"lat\":1.5,\"lon\":-2.25},"
        + "\"notes\":\"" + "n".repeat(1500) + "\",\"tags\":[\"x\",{\"k\":1},[]]},"
        + "\"body\":\"" + "x".repeat(1500) + "\",\"count\":7,\"tail\":\"caf\\u00e9 ☃\"}";

    private static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "split large documents on {0}")
    @MethodSource("targetVersions")
    void writesReplacesAndReportsLargeDocuments(ContainerVersion targetVersion) throws Exception {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var connection = ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext();
            var rest = new RestClient(connection);
            var context = DocumentMigrationTestContext.factory().noOtelTracking().createUnboundRequestContext();
            var failures = mock(FailedDocumentStreamSink.class);
            when(failures.write(any())).thenReturn(Mono.empty());
            var client = new OpenSearchClientFactory(connection).determineVersionAndCreate(100, MAX_REQUEST_BYTES);
            client.setFailedDocumentStreamContext(failures, "session", "worker");
            client.setSplitLargeDocuments(true);

            // A new document arrives as one create and a merge per remaining fragment.
            assertEquals(200, rest.put("plain", "{\"settings\":{\"number_of_shards\":2}}", context).statusCode);
            var written = send(client, context, "plain", "{\"small\":1}", LARGE);
            assertEquals(new MigrationMetrics.Operations(2, 0, 11 + LARGE.getBytes(StandardCharsets.UTF_8).length),
                written.target);
            var stored = get(rest, context, "plain/_doc/doc1?routing=" + ROUTING);
            assertEquals(MAPPER.readTree(LARGE), stored.path("_source"));
            long requests = stored.path("_version").asLong();
            assertTrue(requests >= 5, "one version per fragment: " + requests);
            assertEquals(1, get(rest, context, "plain/_doc/doc0?routing=" + ROUTING).path("_version").asLong());

            // Migrating the ID again replaces the stored document rather than merging into it.
            var changed = LARGE.replace("\"count\":7,", "").replace("\"geo\":{\"lat\":1.5,", "\"geo\":{");
            send(client, context, "plain", "{\"small\":2}", changed);
            stored = get(rest, context, "plain/_doc/doc1?routing=" + ROUTING);
            assertEquals(MAPPER.readTree(changed), stored.path("_source"));
            assertEquals(2 * requests, stored.path("_version").asLong(), "replaced once, then merged");

            // An index that does not keep the whole _source cannot take merges: the document goes whole.
            assertEquals(200, rest.put("partial_source",
                "{\"mappings\":{\"_source\":{\"excludes\":[\"body\"]}}}", context).statusCode);
            send(client, context, "partial_source", "{\"small\":1}", LARGE);
            stored = get(rest, context, "partial_source/_doc/doc1?routing=" + ROUTING);
            assertEquals(1, stored.path("_version").asLong());
            assertFalse(stored.path("_source").has("body"));
            assertTrue(stored.path("_source").has("tail"));
            verify(failures, never()).write(any());

            // A fragment the target rejects is reported once, with the whole document.
            assertEquals(200, rest.put("strict",
                "{\"mappings\":{\"properties\":{\"count\":{\"type\":\"integer\"}}}}", context).statusCode);
            send(client, context, "strict", "{\"small\":1}", LARGE.replace("\"count\":7", "\"count\":\"seven\""));
            var record = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
            verify(failures).write(record.capture());
            assertEquals("doc1", record.getValue().getDocumentId());
            assertEquals("mapper_parsing_exception", record.getValue().getFailureType());
            assertTrue(record.getValue().getResponseItem().has("update"), record.getValue().getResponseItem().toString());
            assertEquals("seven", record.getValue().getRequestItem().path("document").path("count").asText());
            // The fields merged before the rejection stay on the target until the document is migrated again.
            stored = get(rest, context, "strict/_doc/doc1?routing=" + ROUTING);
            assertTrue(stored.path("_source").has("title"));
            assertFalse(stored.path("_source").has("tail"));
        }
    }

    private static OpenSearchClient.BulkResponse send(
        OpenSearchClient client, IRfsContexts.IRequestContext context, String index, String... sources
    ) {
        var documents = new java.util.ArrayList<Document>();
        for (int i = 0; i < sources.length; i++) {
            documents.add(new Document("doc" + i, sources[i].getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(Document.HINT_ROUTING, ROUTING), Map.of()));
        }
        return client.sendBulkRequestRaw(index, List.copyOf(documents), context, false,
            DocumentExceptionAllowlist.empty()).block();
    }

    private static JsonNode get(RestClient rest, IRfsContexts.IRequestContext context, String path) throws Exception {
        var response = rest.get(path, context);
        assertEquals(200, response.statusCode, response.body);
        return MAPPER.readTree(response.body);
    }
}
