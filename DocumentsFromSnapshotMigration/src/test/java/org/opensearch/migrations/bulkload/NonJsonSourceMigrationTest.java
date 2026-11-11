package org.opensearch.migrations.bulkload;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Elasticsearch stores {@code _source} in the request's content type, so documents indexed as
 * SMILE, CBOR, or YAML stay in that format in snapshots and must be converted to JSON for bulk.
 */
@Testcontainers(disabledWithoutDocker = true)
class NonJsonSourceMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "non_json_sources";
    private static final String SNAPSHOT = "non_json_snapshot";
    private static final String DOCUMENT = """
        {"title":"%s","count":42,"ratio":1.5,"tags":["a","b"],"nested":{"flag":true,"nothing":null}}""";
    private static final Map<String, JsonFactory> FORMATS = new LinkedHashMap<>();
    static {
        FORMATS.put("application/json", new JsonFactory());
        FORMATS.put("application/smile", new SmileFactory());
        FORMATS.put("application/cbor", new CBORFactory());
        FORMATS.put("application/yaml", new YAMLFactory());
    }

    @TempDir
    Path directory;

    static Stream<ContainerVersion> sourceVersions() {
        return Stream.of(
            SearchClusterContainer.ES_V5_6_16,
            elasticsearchOss("6.8.23"),
            elasticsearchOss("7.10.2"),
            SearchClusterContainer.ES_V7_17,
            SearchClusterContainer.ES_V8_19,
            SearchClusterContainer.OS_V1_3_20,
            SearchClusterContainer.OS_V2_19_4,
            SearchClusterContainer.OS_LATEST);
    }

    /** The official OSS distribution images, which bundle no x-pack modules. */
    private static ContainerVersion elasticsearchOss(String tag) {
        return new SearchClusterContainer.ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:" + tag, Version.fromString("ES " + tag));
    }

    @Tag("isolatedTest")
    @ParameterizedTest(name = "migrates SMILE, CBOR, and YAML sources from {0}")
    @MethodSource("sourceVersions")
    void migratesNonJsonSources(ContainerVersion sourceVersion) throws Exception {
        Path snapshotDirectory = directory.resolve("snapshot");
        Map<String, JsonNode> expected = createSnapshot(sourceVersion, snapshotDirectory);
        var extractor = SnapshotExtractor.forLocalSnapshot(snapshotDirectory, sourceVersion.getVersion());

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_LATEST)) {
            target.start();
            var connection = ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext();
            var client = new OpenSearchClientFactory(connection).determineVersionAndCreate();
            var failures = mock(FailedDocumentStreamSink.class);
            when(failures.write(any())).thenReturn(Mono.empty());
            client.setFailedDocumentStreamContext(failures, "non-json-sources", "worker");
            try (var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT, directory.resolve("lucene")).build()) {
                var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
                var cursors = new DocumentMigrationPipeline(source, sink, 2, Long.MAX_VALUE, 1, 1)
                    .migrateAll().collectList().block();
                assertNotNull(cursors);
                assertEquals(expected.size(), cursors.stream().mapToLong(c -> c.docsInBatch()).sum());
            }
            verifyNoInteractions(failures);

            var operations = new ClusterOperations(target);
            for (var entry : expected.entrySet()) {
                var response = operations.get("/" + INDEX + "/_doc/" + entry.getKey());
                assertEquals(200, response.getKey(), response.getValue());
                assertEquals(entry.getValue(), JSON.readTree(response.getValue()).path("_source"), entry.getKey());
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
            var http = HttpClient.newHttpClient();
            Map<String, JsonNode> expected = new LinkedHashMap<>();
            for (var format : FORMATS.entrySet()) {
                String id = format.getKey().substring("application/".length());
                JsonNode document = JSON.readTree(DOCUMENT.formatted(id));
                byte[] body = new ObjectMapper(format.getValue()).writeValueAsBytes(document);
                var response = http.send(HttpRequest.newBuilder(
                        URI.create(source.getUrl() + "/" + INDEX + "/" + operations.defaultDocType() + "/" + id))
                    .header("Content-Type", format.getKey())
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(201, response.statusCode(), response.body());
                expected.put(id, document);
            }
            operations.refresh(INDEX);
            operations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, "non_json_repo");
            operations.takeSnapshot("non_json_repo", SNAPSHOT, INDEX, false);
            source.copySnapshotData(snapshotDirectory.toString());
            return expected;
        }
    }
}
