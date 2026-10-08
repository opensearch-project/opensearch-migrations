package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.testfixtures.SupportedClusters;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @ParameterizedTest(name = "bundled external versioning transformation on {0}")
    @MethodSource("targetVersions")
    void writesDocumentsWithExternalVersioningTransform(ContainerVersion targetVersion) throws Exception {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var failedDocuments = mock(FailedDocumentStreamSink.class);
            when(failedDocuments.write(any())).thenReturn(Mono.empty());
            client.setFailedDocumentStreamContext(failedDocuments, "source-field-test", "worker");

            for (String versionType : List.of("external", "external_gte")) {
                String config = """
                    [{
                      "JsonJSTransformerProvider": {
                        "initializationResourcePath": "js/externalVersioning.js",
                        "bindingsObject": {"versionField": "@version_number", "versionType": "%s"}
                      }
                    }]
                    """.formatted(versionType);
                try (var transformer = new TransformationLoader()
                        .getTransformerFactoryFromServiceLoader(config).findFirst().orElseThrow()) {
                    var sink = new OpenSearchDocumentSink(client, () -> transformer, false, DocumentExceptionAllowlist.empty(), null);
                    String index = "sink_versions_" + versionType;
                    sink.createCollection(new CollectionMetadata(index, 1, Map.of())).block();

                    sink.writeBatch(index, List.of(versionedSource(7, "original"))).block();
                    assertStoredVersion(restClient, context, index, 7, "original");

                    // Equal versions conflict under external and overwrite under external_gte.
                    sink.writeBatch(index, List.of(versionedSource(7, "equal"))).block();
                    String equalVersionTitle = versionType.equals("external") ? "original" : "equal";
                    assertStoredVersion(restClient, context, index, 7, equalVersionTitle);

                    // An older version must never replace newer target content.
                    sink.writeBatch(index, List.of(versionedSource(5, "stale"))).block();
                    assertStoredVersion(restClient, context, index, 7, equalVersionTitle);

                    sink.writeBatch(index, List.of(versionedSource(9, "newer"))).block();
                    assertStoredVersion(restClient, context, index, 9, "newer");
                }
            }
            verifyNoInteractions(failedDocuments);
        }
    }

    @ParameterizedTest(name = "default snapshot versions and overrides on {0}")
    @MethodSource("targetVersions")
    void writesDocumentsWithDefaultAndOverriddenSnapshotVersioning(ContainerVersion targetVersion) throws Exception {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var failedDocuments = mock(FailedDocumentStreamSink.class);
            when(failedDocuments.write(any())).thenReturn(Mono.empty());
            client.setFailedDocumentStreamContext(failedDocuments, "snapshot-version-test", "worker");

            for (String versionType : List.of("external", "external_gte", "internal")) {
                String config = versionType.equals("internal")
                    ? "[{\"JsonJSTransformerProvider\":{\"initializationResourcePath\":\"js/internalVersioning.js\"}}]"
                    : "[{\"JsonJSTransformerProvider\":{\"initializationScript\":"
                        + "\"context => documents => documents.map(document => {"
                        + "document.operation.version_type = 'external_gte'; return document; })\"}}]";
                try (var transformer = versionType.equals("external") ? null : new TransformationLoader()
                        .getTransformerFactoryFromServiceLoader(config).findFirst().orElseThrow()) {
                    var sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                        false, DocumentExceptionAllowlist.empty(), null);
                    String index = "snapshot_versions_" + versionType;
                    sink.createCollection(new CollectionMetadata(index, 1, Map.of())).block();

                    sink.writeBatch(index, List.of(snapshotVersion(7, "original"))).block();
                    assertSnapshotWrite(restClient, context, index, versionType.equals("internal") ? 1 : 7, "original");
                    sink.writeBatch(index, List.of(snapshotVersion(7, "equal"))).block();
                    String equalTitle = versionType.equals("external") ? "original" : "equal";
                    assertSnapshotWrite(restClient, context, index, versionType.equals("internal") ? 2 : 7, equalTitle);
                    sink.writeBatch(index, List.of(snapshotVersion(5, "stale"))).block();
                    assertSnapshotWrite(restClient, context, index, versionType.equals("internal") ? 3 : 7,
                        versionType.equals("internal") ? "stale" : equalTitle);

                    // Normal indexing and updating increment the migrated version;
                    // they do not need an external-versioning parameter.
                    var indexed = restClient.put(index + "/_doc/v1", "{\"title\":\"ordinary index\"}",
                        context.createUnboundRequestContext());
                    assertEquals(200, indexed.statusCode);
                    assertSnapshotWrite(restClient, context, index, versionType.equals("internal") ? 4 : 8, "ordinary index");
                    var updated = restClient.post(index + "/_update/v1", "{\"doc\":{\"title\":\"ordinary update\"}}",
                        context.createUnboundRequestContext());
                    assertEquals(200, updated.statusCode);
                    assertSnapshotWrite(restClient, context, index, versionType.equals("internal") ? 5 : 9, "ordinary update");
                }
            }

            // Generated IDs require internal versioning on both the raw and
            // transformed request paths, even when Lucene supplied a version.
            for (boolean transformed : List.of(false, true)) {
                String config = """
                    [{"JsonJSTransformerProvider":{"initializationScript":"context => documents => documents"}}]
                    """;
                try (var transformer = transformed ? new TransformationLoader()
                        .getTransformerFactoryFromServiceLoader(config).findFirst().orElseThrow() : null) {
                    var sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                        true, DocumentExceptionAllowlist.empty(), null);
                    String index = "generated_ids_" + transformed;
                    sink.createCollection(new CollectionMetadata(index, 1, Map.of())).block();
                    sink.writeBatch(index, List.of(snapshotVersion(Long.MAX_VALUE, "generated"))).block();
                    restClient.get(index + "/_refresh", context.createUnboundRequestContext());
                    var response = restClient.get(index + "/_search?version=true", context.createUnboundRequestContext());
                    assertEquals(200, response.statusCode);
                    var hits = MAPPER.readTree(response.body).path("hits").path("hits");
                    assertEquals(1, hits.size());
                    assertFalse(hits.get(0).path("_id").asText().equals("v1"));
                    assertEquals(1L, hits.get(0).path("_version").longValue());
                }
            }
            verifyNoInteractions(failedDocuments);
        }
    }

    private static Document snapshotVersion(long version, String title) {
        return new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "v1", null,
            ("{\"title\":\"" + title + "\"}").getBytes(StandardCharsets.UTF_8),
            null, DocumentChangeType.INDEX, version));
    }

    private static void assertSnapshotWrite(RestClient client, DocumentMigrationTestContext context,
                                            String index, long version, String title) throws Exception {
        var response = client.get(index + "/_doc/v1", context.createUnboundRequestContext());
        assertEquals(200, response.statusCode);
        var document = MAPPER.readTree(response.body);
        assertEquals(version, document.path("_version").longValue());
        assertEquals(title, document.path("_source").path("title").asText());
    }

    @ParameterizedTest(name = "numeric and quoted bulk versions on {0}")
    @MethodSource("targetVersions")
    void bulkAcceptsNumericAndQuotedIntegerVersions(ContainerVersion targetVersion) throws Exception {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            int documentNumber = 0;
            for (String version : List.of("7", "\"7\"", "9007199254740993",
                    "\"9007199254740993\"", "9223372036854775807", "\"9223372036854775807\"")) {
                String id = "d" + documentNumber++;
                String bulk = "{\"index\":{\"_index\":\"wire_versions\",\"_id\":\"" + id
                    + "\",\"version\":" + version + ",\"version_type\":\"external\"}}\n{\"title\":\"wire\"}\n";
                var response = client.post("_bulk", bulk, context.createUnboundRequestContext());
                assertEquals(200, response.statusCode);
                JsonNode result = MAPPER.readTree(response.body);
                assertFalse(result.path("errors").asBoolean(), response.body);
                JsonNode stored = MAPPER.readTree(client.get("wire_versions/_doc/" + id,
                    context.createUnboundRequestContext()).body);
                assertTrue(stored.path("_version").isIntegralNumber());
                assertEquals(Long.parseLong(version.replace("\"", "")), stored.path("_version").longValue());
            }

            // Quoting a version must not bypass the signed 64-bit limit. Include
            // Long.MAX_VALUE with an extra zero, as well as the first overflowing value.
            String maxValueWithExtraZero = Long.toString(Long.MAX_VALUE) + "0";
            for (String version : List.of("9223372036854775808", "\"9223372036854775808\"",
                    maxValueWithExtraZero, "\"" + maxValueWithExtraZero + "\"")) {
                String id = "overflow" + documentNumber++;
                String bulk = "{\"index\":{\"_index\":\"wire_versions\",\"_id\":\"" + id
                    + "\",\"version\":" + version + ",\"version_type\":\"external\"}}\n{\"title\":\"overflow\"}\n";
                var response = client.post("_bulk", bulk, context.createUnboundRequestContext());
                // Numeric parser overflow can surface as HTTP 500. Check the
                // offending value in the error as well as the absence of a write.
                assertTrue(response.statusCode == 400 || response.statusCode == 500, response.body);
                JsonNode error = MAPPER.readTree(response.body).path("error");
                assertTrue(error.path("reason").asText().contains(version.replace("\"", "")), response.body);
                var missing = client.get("wire_versions/_doc/" + id, context.createUnboundRequestContext());
                assertEquals(404, missing.statusCode, "An out-of-range version must not create a document");
            }

            // Query parameters are text on the wire, but the API parses version as an integer.
            var queryResponse = client.put("wire_versions/_doc/query?version=13&version_type=external",
                "{}", context.createUnboundRequestContext());
            assertEquals(201, queryResponse.statusCode);
            assertEquals(13L, MAPPER.readTree(queryResponse.body).path("_version").longValue());
            var fractionalVersion = client.put("wire_versions/_doc/fractional?version=13.5&version_type=external",
                "{}", context.createUnboundRequestContext());
            assertEquals(400, fractionalVersion.statusCode);
            var overflowingQueryVersion = client.put("wire_versions/_doc/overflow-query?version="
                + maxValueWithExtraZero + "&version_type=external", "{}", context.createUnboundRequestContext());
            assertEquals(400, overflowingQueryVersion.statusCode);
            assertEquals(404, client.get("wire_versions/_doc/overflow-query",
                context.createUnboundRequestContext()).statusCode);
        }
    }

    private static Document versionedSource(long version, String title) {
        String source = "{\"@version_number\":" + version + ",\"title\":\"" + title + "\"}";
        return new Document("v1", source.getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
            Map.of(), Map.of());
    }

    private static void assertStoredVersion(RestClient client, DocumentMigrationTestContext context,
                                           String index, long expectedVersion, String expectedTitle) throws Exception {
        var response = client.get(index + "/_doc/v1", context.createUnboundRequestContext());
        assertEquals(200, response.statusCode);
        JsonNode document = MAPPER.readTree(response.body);
        assertEquals(expectedVersion, document.path("_version").asLong());
        assertEquals(expectedVersion, document.path("_source").path("@version_number").asLong());
        assertEquals(expectedTitle, document.path("_source").path("title").asText());
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
