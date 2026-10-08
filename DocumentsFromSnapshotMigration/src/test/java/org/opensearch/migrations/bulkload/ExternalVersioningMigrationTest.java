package org.opensearch.migrations.bulkload;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.testfixtures.SupportedClusters;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ExternalVersioningMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "versioned_documents";
    private static final String SNAPSHOT = "versioned_snapshot";
    private static final Map<String, Long> VERSIONS = Map.of(
        "updated", 2L, "large", 9007199254740993L, "maximum", Long.MAX_VALUE);

    @TempDir
    Path directory;

    static Stream<ContainerVersion> sourceVersions() {
        return SupportedClusters.supportedSources(true).stream();
    }

    @Tag("isolatedTest")
    @ParameterizedTest(name = "preserves Lucene versions from {0}")
    @MethodSource("sourceVersions")
    void preservesSnapshotVersions(ContainerVersion sourceVersion) throws Exception {
        verifySnapshotMigration(sourceVersion, null, 1);
    }

    @Test
    void nativeJavaTransformationReplaysSnapshot() throws Exception {
        try (var transformer = new TransformationLoader().getTransformerFactoryLoader("""
            [{"BulkVersioningTransformerProvider":{"versionType":"external_gte"}}]
            """)) {
            verifySnapshotMigration(SearchClusterContainer.OS_LATEST, transformer, 2);
        }
    }

    private void verifySnapshotMigration(ContainerVersion sourceVersion, IJsonTransformer transformer, int passes) throws Exception {
        Path snapshotDirectory = directory.resolve("snapshot");
        Map<String, JsonNode> sourceDocuments = createSnapshot(sourceVersion, snapshotDirectory);
        var extractor = SnapshotExtractor.forLocalSnapshot(snapshotDirectory, sourceVersion.getVersion());

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_LATEST)) {
            target.start();
            var connection = ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext();
            var client = new OpenSearchClientFactory(connection).determineVersionAndCreate();
            var failures = mock(FailedDocumentStreamSink.class);
            when(failures.write(any())).thenReturn(Mono.empty());
            client.setFailedDocumentStreamContext(failures, "native-versioning", "worker");
            for (int pass = 0; pass < passes; pass++) {
                try (var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT, directory.resolve("lucene-" + pass)).build()) {
                    var sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer,
                        false, DocumentExceptionAllowlist.empty(), null);
                    var pipeline = new DocumentMigrationPipeline(source, sink, 2, Long.MAX_VALUE, 1, 1);
                    var cursors = pipeline.migrateAll().collectList().block();
                    assertNotNull(cursors);
                    assertEquals(VERSIONS.size(), cursors.stream().mapToLong(cursor -> cursor.docsInBatch()).sum());
                }
            }
            verifyNoInteractions(failures);
            var operations = new ClusterOperations(target);
            for (var entry : sourceDocuments.entrySet()) {
                JsonNode migrated = getDocument(operations, entry.getKey());
                assertTrue(migrated.path("_version").isIntegralNumber());
                assertEquals(entry.getValue().path("_version"), migrated.path("_version"), entry.getKey());
                assertEquals(entry.getValue().path("_source"), migrated.path("_source"), entry.getKey());
            }
        }
    }

    private Map<String, JsonNode> createSnapshot(ContainerVersion sourceVersion, Path snapshotDirectory) throws Exception {
        try (var source = new SearchClusterContainer(sourceVersion)) {
            source.start();
            var operations = new ClusterOperations(source);
            operations.createIndex(INDEX, """
                {"settings":{"number_of_shards":1,"number_of_replicas":0}}
                """);
            // The internal version must come from Lucene, not this application field.
            operations.createDocument(INDEX, "updated", "{\"ext_version\":42,\"title\":\"initial\"}");
            operations.createDocument(INDEX, "updated", "{\"ext_version\":42,\"title\":\"updated\"}");
            String bulkPath = "/" + INDEX
                + (UnboundVersionMatchers.isBelowES_7_X.test(sourceVersion.getVersion())
                    ? "/" + operations.defaultDocType() : "")
                + "/_bulk";
            var response = operations.post(bulkPath, """
                {"index":{"_id":"large","version":9007199254740993,"version_type":"external"}}
                {"title":"large"}
                {"index":{"_id":"maximum","version":"9223372036854775807","version_type":"external"}}
                {"title":"maximum"}
                """);
            assertEquals(200, response.getKey());
            assertFalse(MAPPER.readTree(response.getValue()).path("errors").asBoolean(), response.getValue());

            Map<String, JsonNode> documents = new LinkedHashMap<>();
            for (var entry : VERSIONS.entrySet()) {
                JsonNode document = getDocument(operations, entry.getKey());
                assertEquals(entry.getValue().longValue(), document.path("_version").longValue());
                documents.put(entry.getKey(), document);
            }
            operations.refresh(INDEX);
            operations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, "version_repo");
            operations.takeSnapshot("version_repo", SNAPSHOT, INDEX);
            source.copySnapshotData(snapshotDirectory.toString());
            return documents;
        }
    }

    private static JsonNode getDocument(ClusterOperations operations, String id) throws Exception {
        var response = operations.get("/" + INDEX + "/" + operations.defaultDocType() + "/" + id);
        assertEquals(200, response.getKey(), response.getValue());
        return MAPPER.readTree(response.getValue());
    }
}
