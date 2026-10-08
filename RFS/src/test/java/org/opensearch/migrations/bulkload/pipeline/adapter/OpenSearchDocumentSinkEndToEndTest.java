package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.testfixtures.SupportedClusters;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sink-side e2e tests for {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}
 * against real OpenSearch/Elasticsearch clusters via Docker containers.
 */
@Slf4j
@Tag("isolatedTest")
public class OpenSearchDocumentSinkEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "createCollection on {0}")
    @MethodSource("targetVersions")
    void createsIndexOnRealCluster(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);

            var metadata = new CollectionMetadata("sink_test_idx", 1, Map.of());
            sink.createCollection(metadata).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("sink_test_idx", context.createUnboundRequestContext());
            assertThat("Index should exist", resp.statusCode, equalTo(200));
        }
    }

    @ParameterizedTest(name = "writeBatch UPSERT ops on {0}")
    @MethodSource("targetVersions")
    void writesDocumentsToRealCluster(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);

            sink.createCollection(new CollectionMetadata("sink_docs", 1, Map.of())).block();

            var docs = List.of(
                new Document("d1", "{\"title\":\"First\"}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of()),
                new Document("d2", "{\"title\":\"Second\"}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of()),
                new Document("d3", "{\"title\":\"Third\"}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of())
            );

            var cursor = sink.writeBatch("sink_docs", docs).block();

            assertNotNull(cursor);
            assertEquals(3, cursor.docsInBatch());

            verifyDocCount(cluster, "sink_docs", 3);
            log.info("Successfully wrote 3 docs to {} via OpenSearchDocumentSink", targetVersion);
        }
    }

    @ParameterizedTest(name = "writeBatch DELETE ops on {0}")
    @MethodSource("targetVersions")
    void writesDeletesAndIndexOps(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);

            sink.createCollection(new CollectionMetadata("sink_deletes", 1, Map.of())).block();

            var additions = List.of(
                new Document("keep1", "{\"v\":1}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of()),
                new Document("keep2", "{\"v\":2}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of()),
                new Document("to_delete", "{\"v\":3}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of())
            );
            sink.writeBatch("sink_deletes", additions).block();

            var deletions = List.of(
                new Document("to_delete", null, Document.Operation.DELETE, Map.of(), Map.of())
            );
            sink.writeBatch("sink_deletes", deletions).block();

            verifyDocCount(cluster, "sink_deletes", 2);
            log.info("Successfully wrote UPSERT + DELETE ops to {} via OpenSearchDocumentSink", targetVersion);
        }
    }

    @ParameterizedTest(name = "writeBatch with routing on {0}")
    @MethodSource("targetVersions")
    void writesDocumentsWithRouting(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);

            sink.createCollection(new CollectionMetadata("sink_routing", 1, Map.of())).block();

            var docs = List.of(
                new Document("r1", "{\"score\":10}".getBytes(), Document.Operation.UPSERT,
                    Map.of(Document.HINT_ROUTING, "shard_a"), Map.of()),
                new Document("r2", "{\"score\":20}".getBytes(), Document.Operation.UPSERT,
                    Map.of(Document.HINT_ROUTING, "shard_b"), Map.of())
            );
            sink.writeBatch("sink_routing", docs).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", context.createUnboundRequestContext());

            var resp = restClient.get("sink_routing/_doc/r1?routing=shard_a", context.createUnboundRequestContext());
            assertThat("Doc r1 should exist with routing", resp.statusCode, equalTo(200));
            var node = MAPPER.readTree(resp.body);
            assertThat(node.path("_routing").asText(), equalTo("shard_a"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest(name = "versioning policies on {0}")
    @MethodSource("targetVersions")
    void versioningPoliciesSupportReplayAndNormalIngestion(ContainerVersion targetVersion) throws Exception {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var failedDocuments = mock(FailedDocumentStreamSink.class);
            when(failedDocuments.write(any())).thenReturn(Mono.empty());
            client.setFailedDocumentStreamContext(failedDocuments, "version-test", "worker");

            for (String policy : List.of("default", "internal", "external", "external_gte")) {
                boolean internal = policy.equals("internal");
                boolean strict = policy.equals("default") || policy.equals("external");
                try (var transformer = policy.equals("default") ? null : versioningTransformer(Map.of("versionType", policy))) {
                    var sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                        false, DocumentExceptionAllowlist.empty(), null);
                    String index = "versions_" + policy;
                    sink.createCollection(new CollectionMetadata(index, 1, Map.of())).block();
                    sink.writeBatch(index, List.of(snapshotVersion(7, "original"))).block();
                    assertSnapshotWrite(restClient, context, index, internal ? 1 : 7, "original");

                    sink.writeBatch(index, List.of(snapshotVersion(7, "equal"))).block();
                    String equalTitle = strict ? "original" : "equal";
                    assertSnapshotWrite(restClient, context, index, internal ? 2 : 7, equalTitle);
                    sink.writeBatch(index, List.of(snapshotVersion(5, "stale"))).block();
                    assertSnapshotWrite(restClient, context, index, internal ? 3 : 7, internal ? "stale" : equalTitle);

                    if (!internal) {
                        // Opting into the existing allowlist suppresses the failure record, not the version check.
                        var allowlist = new DocumentExceptionAllowlist(Set.of("version_conflict_engine_exception"));
                        var allowedSink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                            false, allowlist, null);
                        allowedSink.writeBatch(index, List.of(snapshotVersion(5, "allowlisted"))).block();
                        assertSnapshotWrite(restClient, context, index, 7, equalTitle);
                    }
                    sink.writeBatch(index, List.of(snapshotVersion(9, "newer"))).block();
                    assertSnapshotWrite(restClient, context, index, internal ? 4 : 9, "newer");

                    // Normal ingestion still increments the stored version after backfill.
                    var indexed = restClient.put(index + "/_doc/v1", "{\"title\":\"ordinary index\"}",
                        context.createUnboundRequestContext());
                    assertEquals(200, indexed.statusCode);
                    assertSnapshotWrite(restClient, context, index, internal ? 5 : 10, "ordinary index");
                    var updated = restClient.post(index + "/_update/v1", "{\"doc\":{\"title\":\"ordinary update\"}}",
                        context.createUnboundRequestContext());
                    assertEquals(200, updated.statusCode);
                    assertSnapshotWrite(restClient, context, index, internal ? 6 : 11, "ordinary update");
                }
            }

            // Application fields can override the snapshot using either numeric or exact string values.
            try (var transformer = versioningTransformer(Map.of("versionType", "external_gte", "versionField", "ext_version"))) {
                var sink = new OpenSearchDocumentSink(client, () -> transformer, false, DocumentExceptionAllowlist.empty(), null);
                sink.createCollection(new CollectionMetadata("application_versions", 1, Map.of())).block();
                for (Object version : List.of(42, "9007199254740993")) {
                    var document = new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "v1", null,
                        MAPPER.writeValueAsBytes(Map.of("title", "application", "ext_version", version)),
                        null, DocumentChangeType.INDEX, 7L));
                    sink.writeBatch("application_versions", List.of(document)).block();
                    var written = assertSnapshotWrite(restClient, context, "application_versions",
                        Long.parseLong(version.toString()), "application");
                    assertEquals(MAPPER.valueToTree(version), written.path("_source").path("ext_version"));
                }
            }

            var failures = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
            verify(failedDocuments, times(5)).write(failures.capture());
            assertEquals(List.of("versions_default", "versions_default", "versions_external", "versions_external", "versions_external_gte"),
                failures.getAllValues().stream().map(FailedDocumentStreamRecord::getTargetIndex).toList());
            for (var failure : failures.getAllValues()) {
                assertEquals("version_conflict_engine_exception", failure.getFailureType());
                assertEquals(FailureClass.NON_RETRYABLE, failure.getFailureClass());
                assertEquals(409, failure.getResponseItem().path("index").path("status").asInt());
                assertTrue(failure.getRequestItem().path("document").has("title"),
                    "Failed document records must include the source even when the request used raw bytes");
            }
        }
    }

    private static IJsonTransformer versioningTransformer(Map<String, Object> config) throws Exception {
        String configuration = MAPPER.writeValueAsString(List.of(Map.of("BulkVersioningTransformerProvider", config)));
        return new TransformationLoader().getTransformerFactoryLoader(configuration);
    }

    private static Document snapshotVersion(long version, String title) {
        return new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "v1", null,
            ("{\"title\":\"" + title + "\"}").getBytes(StandardCharsets.UTF_8),
            null, DocumentChangeType.INDEX, version));
    }

    private static JsonNode assertSnapshotWrite(RestClient client, DocumentMigrationTestContext context,
                                                String index, long version, String title) throws Exception {
        var response = client.get(index + "/_doc/v1", context.createUnboundRequestContext());
        assertEquals(200, response.statusCode);
        var document = MAPPER.readTree(response.body);
        assertEquals(version, document.path("_version").longValue());
        assertEquals(title, document.path("_source").path("title").asText());
        return document;
    }

    @ParameterizedTest(name = "createIndex via metadata sink on {0}")
    @MethodSource("targetVersions")
    void metadataSinkCreatesIndex(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchMetadataSink(client);

            var metadata = new IndexMetadataSnapshot("meta_sink_idx", 2, 0, null, null, null);
            sink.createIndex(metadata).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("meta_sink_idx", context.createUnboundRequestContext());
            assertThat("Index should exist", resp.statusCode, equalTo(200));
        }
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createClient(
        SearchClusterContainer cluster
    ) {
        var connectionContext = ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext();
        return new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
    }

    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
        restClient.get("_refresh", context.createUnboundRequestContext());
        var requests = new SearchClusterRequests(context);
        var counts = requests.getMapOfIndexAndDocCount(restClient);
        assertEquals(expected, counts.getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }
}
