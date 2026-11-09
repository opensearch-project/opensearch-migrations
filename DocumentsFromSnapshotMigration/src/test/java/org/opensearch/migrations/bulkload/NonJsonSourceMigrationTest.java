package org.opensearch.migrations.bulkload;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.smile.databind.SmileMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Elasticsearch and OpenSearch store a document's {@code _source} in the format it was sent in, so a
 * snapshot can hold SMILE, CBOR, and YAML sources next to JSON ones. RFS must migrate all of them.
 */
@Testcontainers(disabledWithoutDocker = true)
class NonJsonSourceMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX = "non_json_sources";
    private static final String SNAPSHOT = "non_json_snapshot";

    @TempDir
    Path directory;

    /**
     * Sources whose distributions are Apache-2.0 licensed. The Elasticsearch 6.8 and 7.10 sources are
     * the OSS distributions; 5.x and earlier tarballs did not bundle any non-OSS modules.
     */
    static Stream<ContainerVersion> sourceVersions() {
        return Stream.of(
            SearchClusterContainer.ES_V5_6_16,
            elasticsearchOss("6.8.23"),
            elasticsearchOss("7.10.2"),
            SearchClusterContainer.OS_V1_3_20,
            SearchClusterContainer.OS_V2_19_4,
            SearchClusterContainer.OS_LATEST
        );
    }

    private static ContainerVersion elasticsearchOss(String tag) {
        return new SearchClusterContainer.ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:" + tag, Version.fromString("ES " + tag));
    }

    private static Map<String, Object> document(String format) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", format);
        doc.put("title", "héllo wörld 世界");
        doc.put("count", 9007199254740993L);
        doc.put("ratio", 0.1);
        doc.put("active", true);
        doc.put("nested", Map.of("tags", List.of("a", "b"), "values", List.of(1, 2, 3)));
        return doc;
    }

    private static Map<String, Map.Entry<String, byte[]>> requestBodies() throws Exception {
        var bodies = new LinkedHashMap<String, Map.Entry<String, byte[]>>();
        bodies.put("json", Map.entry("application/json", JSON.writeValueAsBytes(document("json"))));
        bodies.put("smile", Map.entry("application/smile", new SmileMapper().writeValueAsBytes(document("smile"))));
        bodies.put("cbor", Map.entry("application/cbor", new CBORMapper().writeValueAsBytes(document("cbor"))));
        bodies.put("yaml", Map.entry("application/yaml", new YAMLMapper().writeValueAsBytes(document("yaml"))));
        return bodies;
    }

    @Tag("isolatedTest")
    @ParameterizedTest(name = "migrates SMILE, CBOR, and YAML sources from {0}")
    @MethodSource("sourceVersions")
    void migratesNonJsonSources(ContainerVersion sourceVersion) throws Exception {
        Path snapshotDirectory = directory.resolve("snapshot");
        Map<String, JsonNode> expectedSources = createSnapshot(sourceVersion, snapshotDirectory);
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
                var pipeline = new DocumentMigrationPipeline(source, sink, 10, Long.MAX_VALUE, 1, 1);
                var cursors = pipeline.migrateCollection(INDEX).collectList().block();
                assertEquals(expectedSources.size(), cursors.stream().mapToLong(cursor -> cursor.docsInBatch()).sum());
            }
            verifyNoInteractions(failures);

            var operations = new ClusterOperations(target);
            operations.refresh(INDEX);
            for (var expected : expectedSources.entrySet()) {
                var response = operations.get("/" + INDEX + "/_doc/" + expected.getKey());
                assertEquals(200, response.getKey(), response.getValue());
                assertEquals(expected.getValue(), JSON.readTree(response.getValue()).path("_source"), expected.getKey());
            }
        }
    }

    /** Index one document per content type and return each source as the source cluster renders it in JSON. */
    private Map<String, JsonNode> createSnapshot(ContainerVersion sourceVersion, Path snapshotDirectory) throws Exception {
        try (var source = new SearchClusterContainer(sourceVersion)) {
            source.start();
            var operations = new ClusterOperations(source);
            operations.createIndex(INDEX, """
                {"settings":{"number_of_shards":1,"number_of_replicas":0}}
                """);
            var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            String documentPath = "/" + INDEX + "/" + operations.defaultDocType() + "/";
            for (var body : requestBodies().entrySet()) {
                var request = HttpRequest.newBuilder(URI.create(source.getUrl() + documentPath + body.getKey()))
                    .header("Content-Type", body.getValue().getKey())
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body.getValue().getValue()))
                    .build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(201, response.statusCode(), body.getKey());
            }

            Map<String, JsonNode> expected = new LinkedHashMap<>();
            for (var format : requestBodies().keySet()) {
                var response = operations.get(documentPath + format);
                assertEquals(200, response.getKey(), response.getValue());
                JsonNode rendered = JSON.readTree(response.getValue()).path("_source");
                assertEquals(JSON.valueToTree(document(format)), rendered, format);
                expected.put(format, rendered);
            }
            operations.refresh(INDEX);
            operations.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, "non_json_repo");
            operations.takeSnapshot("non_json_repo", SNAPSHOT, INDEX);
            source.copySnapshotData(snapshotDirectory.toString());
            return expected;
        }
    }
}
