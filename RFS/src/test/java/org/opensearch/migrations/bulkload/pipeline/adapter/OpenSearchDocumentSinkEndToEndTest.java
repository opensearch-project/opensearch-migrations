package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sink-side e2e tests for {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}
 * against real OpenSearch/Elasticsearch clusters via Docker containers.
 *
 * <p>No source snapshot needed — validates the M side of the N+M testing strategy.
 */
@Slf4j
@Tag("isolatedTest")
public class OpenSearchDocumentSinkEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    // --- Document sink tests ---

    @ParameterizedTest(name = "createIndex on {0}")
    @MethodSource("targetVersions")
    void createsIndexOnRealCluster(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);

            var metadata = new IndexMetadataSnapshot("sink_test_idx", 1, 0, null, null, null);
            sink.createIndex(metadata).block();

            // Verify index exists
            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("sink_test_idx", context.createUnboundRequestContext());
            assertThat("Index should exist", resp.statusCode, equalTo(200));
        }
    }

    @ParameterizedTest(name = "writeBatch INDEX ops on {0}")
    @MethodSource("targetVersions")
    void writesDocumentsToRealCluster(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);
            var shardId = new ShardId("snap", "sink_docs", 0);

            // Create index first
            sink.createIndex(new IndexMetadataSnapshot("sink_docs", 1, 0, null, null, null)).block();

            // Write batch of INDEX operations
            var docs = List.of(
                new DocumentChange("d1", null, "{\"title\":\"First\"}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("d2", null, "{\"title\":\"Second\"}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("d3", null, "{\"title\":\"Third\"}".getBytes(), null, DocumentChange.ChangeType.INDEX)
            );

            var cursor = sink.writeBatch(shardId, "sink_docs", docs).block();

            assertNotNull(cursor);
            assertEquals(3, cursor.docsInBatch());

            // Verify docs on cluster
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
            var shardId = new ShardId("snap", "sink_deletes", 0);

            sink.createIndex(new IndexMetadataSnapshot("sink_deletes", 1, 0, null, null, null)).block();

            // Write 3 docs
            var additions = List.of(
                new DocumentChange("keep1", null, "{\"v\":1}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("keep2", null, "{\"v\":2}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("to_delete", null, "{\"v\":3}".getBytes(), null, DocumentChange.ChangeType.INDEX)
            );
            sink.writeBatch(shardId, "sink_deletes", additions).block();

            // Delete one
            var deletions = List.of(
                new DocumentChange("to_delete", null, null, null, DocumentChange.ChangeType.DELETE)
            );
            sink.writeBatch(shardId, "sink_deletes", deletions).block();

            verifyDocCount(cluster, "sink_deletes", 2);
            log.info("Successfully wrote INDEX + DELETE ops to {} via OpenSearchDocumentSink", targetVersion);
        }
    }

    @ParameterizedTest(name = "writeBatch with routing on {0}")
    @MethodSource("targetVersions")
    void writesDocumentsWithRouting(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);
            var shardId = new ShardId("snap", "sink_routing", 0);

            sink.createIndex(new IndexMetadataSnapshot("sink_routing", 1, 0, null, null, null)).block();

            var docs = List.of(
                new DocumentChange("r1", null, "{\"score\":10}".getBytes(), "shard_a", DocumentChange.ChangeType.INDEX),
                new DocumentChange("r2", null, "{\"score\":20}".getBytes(), "shard_b", DocumentChange.ChangeType.INDEX)
            );
            sink.writeBatch(shardId, "sink_routing", docs).block();

            // Verify routing preserved
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

    // --- Metadata sink tests ---

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

    // --- Helpers ---

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
