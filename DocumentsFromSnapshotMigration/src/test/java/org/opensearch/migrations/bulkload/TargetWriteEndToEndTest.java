package org.opensearch.migrations.bulkload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.MigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticDocumentSource;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Target-only tests: validates the writing pipeline against each target version
 * using pre-generated golden IR fixtures. No source cluster needed.
 *
 * Combined with LuceneSnapshotSourceEndToEndTest (source-only), this achieves O(M+N)
 * test coverage instead of O(M*N).
 */
@Slf4j
@Tag("isolatedTest")
public class TargetWriteEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Paths.get(System.getProperty("user.dir"))
        .resolve("../RFS/test-resources/golden");

    static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "Golden docs → {0}")
    @MethodSource("targetVersions")
    void writeGoldenDocumentsToTarget(ContainerVersion targetVersion) throws Exception {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var sink = createSink(cluster);
            var shardId = new ShardId("snap", "golden_test", 0);

            sink.createIndex(new IndexMetadataSnapshot("golden_test", 1, 0, null, null, null)).block();

            var docs = loadGoldenDocs("es710-wsoft-docs.json");
            sink.writeBatch(shardId, "golden_test", docs).block();

            verifyDocCount(cluster, "golden_test", docs.size());
        }
    }

    @ParameterizedTest(name = "Routing → {0}")
    @MethodSource("targetVersions")
    void writeDocumentsWithRoutingToTarget(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var sink = createSink(cluster);
            var shardId = new ShardId("snap", "routed_test", 0);

            sink.createIndex(new IndexMetadataSnapshot("routed_test", 1, 0, null, null, null)).block();

            var docs = List.of(
                new DocumentChange("r1", null, "{\"score\":10}".getBytes(StandardCharsets.UTF_8), "shard_a", DocumentChange.ChangeType.INDEX),
                new DocumentChange("r2", null, "{\"score\":20}".getBytes(StandardCharsets.UTF_8), "shard_b", DocumentChange.ChangeType.INDEX)
            );
            sink.writeBatch(shardId, "routed_test", docs).block();

            verifyDocCount(cluster, "routed_test", 2);
            verifyRouting(cluster, "routed_test", "r1", "shard_a");
        }
    }

    @ParameterizedTest(name = "Deletes → {0}")
    @MethodSource("targetVersions")
    void writeDocumentsWithDeletesToTarget(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var sink = createSink(cluster);
            var shardId = new ShardId("snap", "delete_test", 0);

            sink.createIndex(new IndexMetadataSnapshot("delete_test", 1, 0, null, null, null)).block();

            // Write 3 docs
            sink.writeBatch(shardId, "delete_test", List.of(
                new DocumentChange("keep1", null, "{\"v\":1}".getBytes(StandardCharsets.UTF_8), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("keep2", null, "{\"v\":2}".getBytes(StandardCharsets.UTF_8), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("to_delete", null, "{\"v\":3}".getBytes(StandardCharsets.UTF_8), null, DocumentChange.ChangeType.INDEX)
            )).block();

            // Delete one
            sink.writeBatch(shardId, "delete_test", List.of(
                new DocumentChange("to_delete", null, null, null, DocumentChange.ChangeType.DELETE)
            )).block();

            verifyDocCount(cluster, "delete_test", 2);
        }
    }

    @ParameterizedTest(name = "Pipeline sink → {0}")
    @MethodSource("targetVersions")
    void pipelineSinkWritesToTarget(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);

            var source = new SyntheticDocumentSource("pipeline_sink_test", 1, 5);
            sink.createIndex(new IndexMetadataSnapshot("pipeline_sink_test", 1, 0, null, null, null)).block();

            var pipeline = new MigrationPipeline(source, sink, 1000, Long.MAX_VALUE);
            var cursors = pipeline.migrateAll().collectList().block();

            assertNotNull(cursors);
            verifyDocCount(cluster, "pipeline_sink_test", 5);
        }
    }

    // --- Helpers ---

    private static OpenSearchDocumentSink createSink(SearchClusterContainer cluster) {
        return new OpenSearchDocumentSink(createClient(cluster));
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createClient(SearchClusterContainer cluster) {
        var ctx = ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext();
        return new OpenSearchClientFactory(ctx).determineVersionAndCreate();
    }

    private static List<DocumentChange> loadGoldenDocs(String filename) throws IOException {
        var path = GOLDEN_DIR.resolve(filename);
        var nodes = MAPPER.readValue(Files.readString(path), new TypeReference<List<GoldenDoc>>() {});
        return nodes.stream()
            .map(g -> new DocumentChange(
                g.id, g.type,
                g.source != null ? g.source.getBytes(StandardCharsets.UTF_8) : null,
                g.routing, DocumentChange.ChangeType.INDEX
            ))
            .toList();
    }

    @SneakyThrows
    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
        restClient.get("_refresh", context.createUnboundRequestContext());
        var counts = new SearchClusterRequests(context).getMapOfIndexAndDocCount(restClient);
        assertEquals(expected, counts.getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }

    @SneakyThrows
    private static void verifyRouting(SearchClusterContainer cluster, String indexName, String docId, String expectedRouting) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
        var resp = restClient.get(indexName + "/_doc/" + docId + "?routing=" + expectedRouting,
            context.createUnboundRequestContext());
        assertEquals(200, resp.statusCode, "Doc " + docId + " should exist with routing " + expectedRouting);
        var node = MAPPER.readTree(resp.body);
        assertEquals(expectedRouting, node.path("_routing").asText());
    }

    private static class GoldenDoc {
        public String id;
        public String type;
        public String source;
        public String routing;
        public String operation;
    }
}
