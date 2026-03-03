package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;

import org.opensearch.migrations.bulkload.SupportedClusters;
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
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sink-side E2E tests for {@link OpenSearchDocumentSink} against real OpenSearch clusters.
 * Validates the M side of the N+M testing strategy — no source snapshot needed.
 */
@Slf4j
@Tag("isolatedTest")
public class OpenSearchDocumentSinkEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createsIndexOnRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);

            sink.createIndex(new IndexMetadataSnapshot("sink_test_idx", 1, 0, null, null, null)).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("sink_test_idx", context.createUnboundRequestContext());
            assertThat("Index should exist", resp.statusCode, equalTo(200));
        }
    }

    @Test
    void writesDocumentsToRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);
            var shardId = new ShardId("snap", "sink_docs", 0);

            sink.createIndex(new IndexMetadataSnapshot("sink_docs", 1, 0, null, null, null)).block();

            var docs = List.of(
                new DocumentChange("d1", null, "{\"title\":\"First\"}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("d2", null, "{\"title\":\"Second\"}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("d3", null, "{\"title\":\"Third\"}".getBytes(), null, DocumentChange.ChangeType.INDEX)
            );
            var cursor = sink.writeBatch(shardId, "sink_docs", docs).block();

            assertNotNull(cursor);
            assertEquals(3, cursor.docsInBatch());
            verifyDocCount(cluster, "sink_docs", 3);
        }
    }

    @Test
    void writesDeletesAndIndexOps() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);
            var shardId = new ShardId("snap", "sink_deletes", 0);

            sink.createIndex(new IndexMetadataSnapshot("sink_deletes", 1, 0, null, null, null)).block();

            // Write 3 docs
            sink.writeBatch(shardId, "sink_deletes", List.of(
                new DocumentChange("keep1", null, "{\"v\":1}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("keep2", null, "{\"v\":2}".getBytes(), null, DocumentChange.ChangeType.INDEX),
                new DocumentChange("to_delete", null, "{\"v\":3}".getBytes(), null, DocumentChange.ChangeType.INDEX)
            )).block();

            // Delete one
            sink.writeBatch(shardId, "sink_deletes", List.of(
                new DocumentChange("to_delete", null, null, null, DocumentChange.ChangeType.DELETE)
            )).block();

            verifyDocCount(cluster, "sink_deletes", 2);
        }
    }

    @Test
    void writesDocumentsWithRouting() throws Exception {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);
            var shardId = new ShardId("snap", "sink_routing", 0);

            sink.createIndex(new IndexMetadataSnapshot("sink_routing", 1, 0, null, null, null)).block();

            sink.writeBatch(shardId, "sink_routing", List.of(
                new DocumentChange("r1", null, "{\"score\":10}".getBytes(), "shard_a", DocumentChange.ChangeType.INDEX),
                new DocumentChange("r2", null, "{\"score\":20}".getBytes(), "shard_b", DocumentChange.ChangeType.INDEX)
            )).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", context.createUnboundRequestContext());

            var resp = restClient.get("sink_routing/_doc/r1?routing=shard_a", context.createUnboundRequestContext());
            assertThat("Doc r1 should exist with routing", resp.statusCode, equalTo(200));
            var node = MAPPER.readTree(resp.body);
            assertThat(node.path("_routing").asText(), equalTo("shard_a"));
        }
    }

    @Test
    void metadataSinkCreatesIndex() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchMetadataSink(client);

            sink.createIndex(new IndexMetadataSnapshot("meta_sink_idx", 2, 0, null, null, null)).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("meta_sink_idx", context.createUnboundRequestContext());
            assertThat("Index should exist", resp.statusCode, equalTo(200));
        }
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createClient(SearchClusterContainer cluster) {
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
