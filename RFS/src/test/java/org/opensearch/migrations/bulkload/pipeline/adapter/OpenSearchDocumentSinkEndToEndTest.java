package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;
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
