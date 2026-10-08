package org.opensearch.migrations.bulkload;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("isolatedTest")
class ExternalVersioningMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "versioned_documents";
    private static final String SNAPSHOT = "versioned_snapshot";
    private static final List<String> IDS = List.of("updated", "external", "large", "maximum");
    private static final DocumentExceptionAllowlist ALLOW_VERSION_CONFLICTS =
        new DocumentExceptionAllowlist(Set.of("version_conflict_engine_exception"));
    private static final String SOURCE_FIELD_TRANSFORM = """
        [{
          "JsonJSTransformerProvider": {
            "initializationResourcePath": "js/externalVersioning.js",
            "bindingsObject": {"versionField": "ext_version"}
          }
        }]
        """;

    @TempDir
    Path directory;

    static Stream<ContainerVersion> sourceVersions() {
        return Stream.of(
            SearchClusterContainer.ES_V1_7_6,
            SearchClusterContainer.ES_V2_4_6,
            SearchClusterContainer.ES_V5_6_16,
            SearchClusterContainer.ES_V6_8_23,
            SearchClusterContainer.ES_V7_10_2,
            SearchClusterContainer.ES_V8_19,
            SearchClusterContainer.ES_V9_1,
            SearchClusterContainer.OS_V1_3_20,
            SearchClusterContainer.OS_V2_19_4,
            SearchClusterContainer.OS_V3_7_0
        );
    }

    @ParameterizedTest(name = "default preservation and all three bundled versioning modes from {0}")
    @MethodSource("sourceVersions")
    void preservesSnapshotVersionsAndSupportsSourceFields(ContainerVersion sourceVersion) throws Exception {
        Path snapshotDirectory = directory.resolve("snapshot");
        Map<String, JsonNode> sourceDocuments = createSnapshot(sourceVersion, snapshotDirectory);
        var extractor = SnapshotExtractor.forLocalSnapshot(snapshotDirectory, sourceVersion.getVersion());

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_LATEST)) {
            target.start();
            var operations = new ClusterOperations(target);

            // Default writes preserve versions directly, without loading a transformation.
            migrate(extractor, target, "default", null);
            assertSnapshotVersions(operations, sourceDocuments);

            // Strict external replays report conflicts unless explicitly allowlisted.
            migrate(extractor, target, "retry", null, DocumentExceptionAllowlist.empty(), IDS);
            assertSnapshotVersions(operations, sourceDocuments);
            migrate(extractor, target, "retry-allowed", null, ALLOW_VERSION_CONFLICTS, List.of());
            assertSnapshotVersions(operations, sourceDocuments);

            // Ordinary ingestion can continue after backfill. An older snapshot
            // must not overwrite the incremented version or the new content.
            var advanced = operations.put("/" + INDEX + "/_doc/external",
                "{\"ext_version\":8,\"title\":\"newer target content\"}");
            assertEquals(200, advanced.getKey());
            assertEquals(8L, MAPPER.readTree(advanced.getValue()).path("_version").longValue());
            migrate(extractor, target, "stale", null, DocumentExceptionAllowlist.empty(), IDS);
            assertEquals(8L, getDocument(operations, "external").path("_version").longValue());
            assertEquals("newer target content",
                getDocument(operations, "external").path("_source").path("title").asText());

            // external_gte accepts equal versions, but a newer target remains a
            // document failure unless the existing exception allowlist suppresses it.
            migrate(extractor, target, "gte-stale", versioningConfig("external_gte"),
                DocumentExceptionAllowlist.empty(), List.of("external"));
            migrate(extractor, target, "gte-stale-allowed", versioningConfig("external_gte"),
                ALLOW_VERSION_CONFLICTS, List.of());
            assertEquals(8L, getDocument(operations, "external").path("_version").longValue());
            assertEquals("newer target content",
                getDocument(operations, "external").path("_source").path("title").asText());

            // Any custom script receives the default action version as an exact
            // decimal string, even when the script only inspects the metadata.
            assertEquals(200, operations.delete("/" + INDEX).getKey());
            migrate(extractor, target, "custom-inspect", customTransformerConfig(null));
            assertSnapshotVersions(operations, sourceDocuments);

            // The shared modifier accepts explicit external mode as well as the native default.
            assertEquals(200, operations.delete("/" + INDEX).getKey());
            migrate(extractor, target, "bundled-external", versioningConfig("external"));
            assertSnapshotVersions(operations, sourceDocuments);

            // Both a one-field custom override and the bundled external_gte
            // configuration can overwrite equal versions while retaining the number.
            int override = 0;
            for (String config : List.of(customTransformerConfig("external_gte"), versioningConfig("external_gte"))) {
                var equalVersionWrite = operations.put("/" + INDEX + "/_doc/external?version=7&version_type=external_gte",
                    "{\"ext_version\":7,\"title\":\"equal-version target content\"}");
                assertEquals(200, equalVersionWrite.getKey());
                migrate(extractor, target, "strict-equal-" + override, null, DocumentExceptionAllowlist.empty(), IDS);
                assertEquals("equal-version target content",
                    getDocument(operations, "external").path("_source").path("title").asText());
                migrate(extractor, target, "gte-" + override++, config);
                assertSnapshotVersions(operations, sourceDocuments);
            }

            assertEquals(200, operations.delete("/" + INDEX).getKey());
            migrate(extractor, target, "custom-internal", customTransformerConfig("internal"));
            assertInternalVersions(operations, sourceDocuments, 1);

            assertEquals(200, operations.delete("/" + INDEX).getKey());
            migrate(extractor, target, "bundled-internal", versioningConfig("internal"));
            assertInternalVersions(operations, sourceDocuments, 1);
            migrate(extractor, target, "bundled-internal-retry", versioningConfig("internal"));
            assertInternalVersions(operations, sourceDocuments, 2);

            assertEquals(200, operations.delete("/" + INDEX).getKey());
            migrate(extractor, target, "source-field", SOURCE_FIELD_TRANSFORM);
            for (String id : IDS) {
                JsonNode document = getDocument(operations, id);
                JsonNode originalSource = sourceDocuments.get(id).path("_source");
                assertEquals(originalSource.path("ext_version").asLong(), document.path("_version").longValue());
                assertEquals(originalSource, document.path("_source"));
            }
        }
    }

    private static String versioningConfig(String versionType) {
        return """
            [{
              "JsonJSTransformerProvider": {
                "initializationResourcePath": "js/externalVersioning.js",
                "bindingsObject": {"versionType": "%s"}
              }
            }]
            """.formatted(versionType);
    }

    private static String customTransformerConfig(String versionType) throws Exception {
        String script = """
            context => documents => documents.map(document => {
                const version = document.source_metadata._version;
                if (typeof version !== "string" || document.operation.version !== version
                    || document.operation.version_type !== "external") {
                    throw new Error("Default action versions must preserve the snapshot version as a decimal string");
                }
                if (context.versionType === "internal") {
                    delete document.operation.version;
                    delete document.operation.version_type;
                } else if (context.versionType != null) {
                    document.operation.version_type = context.versionType;
                }
                return document;
            })
            """;
        return MAPPER.writeValueAsString(List.of(Map.of("JsonJSTransformerProvider", Map.of(
            "initializationScript", script,
            "bindingsObject", versionType == null ? Map.of() : Map.of("versionType", versionType)
        ))));
    }

    private Map<String, JsonNode> createSnapshot(ContainerVersion sourceVersion, Path snapshotDirectory) throws Exception {
        try (var source = new SearchClusterContainer(sourceVersion)) {
            source.start();
            var operations = new ClusterOperations(source);
            operations.createIndex(INDEX, """
                {"settings":{"number_of_shards":1,"number_of_replicas":0}}
                """);
            operations.createDocument(INDEX, "updated", "{\"ext_version\":42,\"title\":\"initial\"}");
            operations.createDocument(INDEX, "updated", "{\"ext_version\":42,\"title\":\"updated\"}");
            // Exercise numeric and quoted versions at the source API, including the
            // full 64-bit range that JavaScript Numbers cannot represent exactly.
            String bulkPath = "/" + INDEX
                + (UnboundVersionMatchers.isBelowES_7_X.test(sourceVersion.getVersion())
                    ? "/" + operations.defaultDocType() : "")
                + "/_bulk";
            var response = operations.post(bulkPath, """
                {"index":{"_id":"external","version":"7","version_type":"external"}}
                {"ext_version":"9007199254740993","title":"external"}
                {"index":{"_id":"large","version":9007199254740993,"version_type":"external"}}
                {"ext_version":3,"title":"large"}
                {"index":{"_id":"maximum","version":"9223372036854775807","version_type":"external"}}
                {"ext_version":4,"title":"maximum"}
                """);
            assertEquals(200, response.getKey());
            assertFalse(MAPPER.readTree(response.getValue()).path("errors").asBoolean(), response.getValue());

            Map<String, JsonNode> documents = new LinkedHashMap<>();
            for (String id : IDS) {
                documents.put(id, getDocument(operations, id));
            }
            assertEquals(2L, documents.get("updated").path("_version").longValue());
            assertEquals(7L, documents.get("external").path("_version").longValue());
            assertEquals(9007199254740993L, documents.get("large").path("_version").longValue());
            assertEquals(Long.MAX_VALUE, documents.get("maximum").path("_version").longValue());

            operations.refresh(INDEX);
            operations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, "version_repo");
            operations.takeSnapshot("version_repo", SNAPSHOT, INDEX);
            source.copySnapshotData(snapshotDirectory.toString());
            return documents;
        }
    }

    private void migrate(SnapshotExtractor extractor, SearchClusterContainer target, String workDirectory,
                         String config) throws Exception {
        migrate(extractor, target, workDirectory, config, DocumentExceptionAllowlist.empty(), List.of());
    }

    private void migrate(SnapshotExtractor extractor, SearchClusterContainer target, String workDirectory,
                         String config, DocumentExceptionAllowlist allowlist, List<String> expectedConflicts) throws Exception {
        var connection = ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext();
        var client = new OpenSearchClientFactory(connection).determineVersionAndCreate();
        var failedDocuments = mock(FailedDocumentStreamSink.class);
        when(failedDocuments.write(any())).thenReturn(Mono.empty());
        client.setFailedDocumentStreamContext(failedDocuments, "version-test", workDirectory);
        try (var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT, directory.resolve(workDirectory)).build();
             var transformer = config == null ? null
                 : new TransformationLoader().getTransformerFactoryLoader(config)) {
            var sink = new OpenSearchDocumentSink(client, transformer == null ? null : () -> transformer, false,
                allowlist, null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 2, Long.MAX_VALUE, 1, 1);
            var cursors = pipeline.migrateAll().collectList().block();
            assertNotNull(cursors);
            assertEquals(4L, cursors.stream().mapToLong(cursor -> cursor.docsInBatch()).sum());
            if (expectedConflicts.isEmpty()) {
                verifyNoInteractions(failedDocuments);
            } else {
                var failures = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
                verify(failedDocuments, times(expectedConflicts.size())).write(failures.capture());
                assertEquals(expectedConflicts.stream().sorted().toList(),
                    failures.getAllValues().stream().map(FailedDocumentStreamRecord::getDocumentId).sorted().toList());
                for (var failure : failures.getAllValues()) {
                    assertEquals("version_conflict_engine_exception", failure.getFailureType());
                    assertEquals(FailureClass.NON_RETRYABLE, failure.getFailureClass());
                    assertEquals(409, failure.getResponseItem().path("index").path("status").asInt());
                }
            }
        }
    }

    private static void assertSnapshotVersions(ClusterOperations operations,
                                              Map<String, JsonNode> sourceDocuments) throws Exception {
        for (String id : IDS) {
            JsonNode target = getDocument(operations, id);
            assertTrue(target.path("_version").isIntegralNumber());
            assertEquals(sourceDocuments.get(id).path("_version"), target.path("_version"), id);
            assertEquals(sourceDocuments.get(id).path("_source"), target.path("_source"), id);
        }
    }

    private static void assertInternalVersions(ClusterOperations operations,
                                              Map<String, JsonNode> sourceDocuments, long expectedVersion) throws Exception {
        for (String id : IDS) {
            JsonNode document = getDocument(operations, id);
            assertEquals(expectedVersion, document.path("_version").longValue(), id);
            assertEquals(sourceDocuments.get(id).path("_source"), document.path("_source"), id);
        }
    }

    private static JsonNode getDocument(ClusterOperations operations, String id) throws Exception {
        var response = operations.get("/" + INDEX + "/" + operations.defaultDocType() + "/" + id);
        assertEquals(200, response.getKey(), response.getValue());
        return MAPPER.readTree(response.getValue());
    }
}
