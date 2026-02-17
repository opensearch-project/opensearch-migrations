package org.opensearch.migrations.bulkload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.DocumentReaderEngine.DocumentChangeset;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;

/**
 * Target-only tests: validates the writing pipeline against each target version
 * using pre-generated golden IR fixtures. No source cluster needed.
 *
 * Combined with SnapshotReaderEndToEndTest (source-only), this achieves O(M+N)
 * test coverage instead of O(M*N).
 */
@Tag("isolatedTest")
public class TargetWriteEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Paths.get(System.getProperty("user.dir"))
        .resolve("../RFS/test-resources/golden");

    private static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "Target {0}")
    @MethodSource("targetVersions")
    void writeGoldenDocumentsToTarget(SearchClusterContainer.ContainerVersion targetVersion) {
        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            writeAndVerifyGoldenDocs(targetCluster, "es710-wsoft-docs.json", "golden_test", 3);
        }
    }

    @ParameterizedTest(name = "Target {0} with routing")
    @MethodSource("targetVersions")
    void writeDocumentsWithRoutingToTarget(SearchClusterContainer.ContainerVersion targetVersion) {
        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            writeAndVerifyRoutedDocs(targetCluster);
        }
    }

    @ParameterizedTest(name = "Target {0} with deletes")
    @MethodSource("targetVersions")
    void writeDocumentsWithDeletesToTarget(SearchClusterContainer.ContainerVersion targetVersion) {
        try (var targetCluster = new SearchClusterContainer(targetVersion)) {
            targetCluster.start();
            writeAndVerifyDocsWithDeletes(targetCluster);
        }
    }

    @SneakyThrows
    private void writeAndVerifyGoldenDocs(
        SearchClusterContainer targetCluster,
        String goldenFile,
        String indexName,
        int expectedDocCount
    ) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var docs = loadGoldenDocs(goldenFile);

        createIndex(targetCluster, indexName, 1);
        reindexDocs(targetCluster, indexName, docs, context);
        refreshAndVerifyDocCount(targetCluster, indexName, expectedDocCount, context);
    }

    @SneakyThrows
    private void writeAndVerifyRoutedDocs(SearchClusterContainer targetCluster) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var indexName = "routed_test";

        var docs = List.of(
            new LuceneDocumentChange(0, "r1", null, "{\"score\": 10}", "shard_a", DocumentChangeType.INDEX),
            new LuceneDocumentChange(1, "r2", null, "{\"score\": 20}", "shard_a", DocumentChangeType.INDEX),
            new LuceneDocumentChange(2, "r3", null, "{\"score\": 30}", "shard_b", DocumentChangeType.INDEX)
        );

        createIndex(targetCluster, indexName, 1);
        reindexDocs(targetCluster, indexName, docs, context);

        var targetClient = createRestClient(targetCluster);
        targetClient.get("_refresh", context.createUnboundRequestContext());

        // Verify routing is preserved on individual documents
        var mapper = new ObjectMapper();
        for (var doc : docs) {
            var resp = targetClient.get(indexName + "/_doc/" + doc.id + "?routing=" + doc.routing,
                context.createUnboundRequestContext());
            Assertions.assertEquals(200, resp.statusCode, "Doc " + doc.id + " should exist");
            var node = mapper.readTree(resp.body);
            Assertions.assertEquals(doc.routing, node.path("_routing").asText(),
                "Routing should be preserved for doc " + doc.id);
        }
    }

    @SneakyThrows
    private void writeAndVerifyDocsWithDeletes(SearchClusterContainer targetCluster) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var indexName = "delete_test";

        // Index 3 docs, then delete one
        var additions = List.<LuceneDocumentChange>of(
            new LuceneDocumentChange(0, "keep1", null, "{\"val\": 1}", null, DocumentChangeType.INDEX),
            new LuceneDocumentChange(1, "keep2", null, "{\"val\": 2}", null, DocumentChangeType.INDEX),
            new LuceneDocumentChange(2, "to_delete", null, "{\"val\": 3}", null, DocumentChangeType.INDEX)
        );
        var deletions = List.<LuceneDocumentChange>of(
            new LuceneDocumentChange(3, "to_delete", null, "{\"val\": 3}", null, DocumentChangeType.DELETE)
        );

        createIndex(targetCluster, indexName, 1);

        // Write additions first
        reindexDocs(targetCluster, indexName, additions, context);
        // Then apply deletions
        reindexChangeset(targetCluster, indexName, deletions, List.of(), context);

        refreshAndVerifyDocCount(targetCluster, indexName, 2, context);
    }

    private List<LuceneDocumentChange> loadGoldenDocs(String filename) throws IOException {
        var path = GOLDEN_DIR.resolve(filename);
        var nodes = MAPPER.readValue(Files.readString(path), new TypeReference<List<GoldenDoc>>() {});
        return nodes.stream()
            .map(g -> new LuceneDocumentChange(
                0, g.id, g.type, g.source, g.routing,
                DocumentChangeType.valueOf(g.operation)
            ))
            .toList();
    }

    @SneakyThrows
    private void createIndex(SearchClusterContainer cluster, String indexName, int shards) {
        new ClusterOperations(cluster).createIndex(indexName, String.format(
            "{\"settings\":{\"number_of_shards\":%d,\"number_of_replicas\":0}}", shards));
    }

    /** Reindex a list of docs (all treated as additions) */
    @SneakyThrows
    private void reindexDocs(
        SearchClusterContainer cluster,
        String indexName,
        List<LuceneDocumentChange> docs,
        DocumentMigrationTestContext context
    ) {
        reindexChangeset(cluster, indexName, List.of(), docs, context);
    }

    /** Reindex a changeset with explicit deletions and additions */
    @SneakyThrows
    private void reindexChangeset(
        SearchClusterContainer cluster,
        String indexName,
        List<LuceneDocumentChange> deletions,
        List<LuceneDocumentChange> additions,
        DocumentMigrationTestContext context
    ) {
        var connectionContext = ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext();
        var reindexer = new DocumentReindexer(
            new OpenSearchClientFactory(connectionContext).determineVersionAndCreate(),
            1000, Long.MAX_VALUE, 1, null, false, DocumentExceptionAllowlist.empty()
        );

        var changeset = new DocumentChangeset(
            Flux.fromIterable(deletions),
            Flux.fromIterable(additions),
            () -> {}
        );

        reindexer.reindex(indexName, changeset, context.createReindexContext())
            .blockLast();
    }

    private RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
    }

    @SneakyThrows
    private void refreshAndVerifyDocCount(
        SearchClusterContainer cluster,
        String indexName,
        int expectedCount,
        DocumentMigrationTestContext context
    ) {
        var targetClient = createRestClient(cluster);
        targetClient.get("_refresh", context.createUnboundRequestContext());

        var requests = new SearchClusterRequests(context);
        var docCounts = requests.getMapOfIndexAndDocCount(targetClient);
        Assertions.assertEquals(expectedCount, docCounts.getOrDefault(indexName, 0),
            "Expected " + expectedCount + " docs in " + indexName);
    }

    /** Simple POJO for deserializing golden fixture JSON */
    private static class GoldenDoc {
        public String id;
        public String type;
        public String source;
        public String routing;
        public String operation;
    }
}
