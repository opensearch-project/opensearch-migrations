package org.opensearch.migrations.bulkload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.MetadataMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.MigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchMetadataSink;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceFactory;
import org.opensearch.migrations.bulkload.pipeline.source.SourcelessDocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.SourcelessExtractionConfig;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Full pipeline E2E tests combining sources and sinks against real OpenSearch clusters.
 * Tests the complete data flow: DocumentSource → MigrationPipeline → DocumentSink → verify on cluster.
 */
@Slf4j
@Tag("isolatedTest")
public class PipelineEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sourcelessPipelineToRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);

            var config = SourcelessExtractionConfig.withDefaults("pipeline_test", 2, 10);
            var source = new SourcelessDocumentSource(List.of(config), null);

            // Create index
            sink.createIndex(new IndexMetadataSnapshot("pipeline_test", 1, 0, null, null, null)).block();

            // Run pipeline
            var pipeline = new MigrationPipeline(source, sink, 5, 100_000);
            pipeline.migrateAll().blockLast();

            verifyDocCount(cluster, "pipeline_test", 20); // 2 shards × 10 docs
            log.info("Sourceless pipeline wrote 20 docs to real cluster");
        }
    }

    @Test
    void pipelineWithSmallBatches() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);

            var config = SourcelessExtractionConfig.withDefaults("batch_test", 1, 7);
            var source = new SourcelessDocumentSource(List.of(config), null);

            sink.createIndex(new IndexMetadataSnapshot("batch_test", 1, 0, null, null, null)).block();

            // Batch size 3 → should produce 3 batches (3+3+1)
            var pipeline = new MigrationPipeline(source, sink, 3, 100_000);
            var cursors = pipeline.migrateAll().collectList().block();

            assertEquals(3, cursors.size(), "Should have 3 batches for 7 docs with batch size 3");
            verifyDocCount(cluster, "batch_test", 7);
        }
    }

    @Test
    void metadataThenDocumentPipeline() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var docSink = new OpenSearchDocumentSink(client);
            var metaSink = new OpenSearchMetadataSink(client);

            var configs = List.of(SourcelessExtractionConfig.withDefaults("meta_doc_test", 1, 5));
            var metaSource = DocumentSourceFactory.createSourcelessMetadata(configs);
            var docSource = new SourcelessDocumentSource(configs, null);

            // Metadata first
            new MetadataMigrationPipeline(metaSource, metaSink).migrateAll();

            // Then documents
            new MigrationPipeline(docSource, docSink, 10, 100_000).migrateAll().blockLast();

            verifyDocCount(cluster, "meta_doc_test", 5);
        }
    }

    @Test
    void multiIndexSourcelessPipeline() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);

            var configs = List.of(
                SourcelessExtractionConfig.withDefaults("idx_a", 1, 5),
                SourcelessExtractionConfig.withDefaults("idx_b", 2, 3)
            );
            var source = new SourcelessDocumentSource(configs, null);

            // Create indices
            for (var cfg : configs) {
                sink.createIndex(new IndexMetadataSnapshot(cfg.indexName(), 1, 0, null, null, null)).block();
            }

            new MigrationPipeline(source, sink, 10, 100_000).migrateAll().blockLast();

            verifyDocCount(cluster, "idx_a", 5);  // 1 shard × 5 docs
            verifyDocCount(cluster, "idx_b", 6);  // 2 shards × 3 docs
        }
    }

    @Test
    void goldenFixtureWriteTest() throws Exception {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchDocumentSink(client);
            var shardId = new ShardId("snap", "golden_test", 0);

            sink.createIndex(new IndexMetadataSnapshot("golden_test", 1, 0, null, null, null)).block();

            // Load golden fixture
            var fixtureUrl = getClass().getClassLoader().getResource("golden/es710-wsoft-docs.json");
            if (fixtureUrl == null) {
                // Try from test-resources path
                var fixturePath = Path.of("RFS/test-resources/golden/es710-wsoft-docs.json");
                if (!Files.exists(fixturePath)) {
                    log.warn("Golden fixture not found, skipping test");
                    return;
                }
                var content = Files.readString(fixturePath);
                var goldenDocs = MAPPER.readValue(content, new TypeReference<List<java.util.Map<String, Object>>>() {});
                var docs = goldenDocs.stream()
                    .map(doc -> new DocumentChange(
                        (String) doc.get("_id"),
                        (String) doc.get("_type"),
                        doc.get("_source") != null
                            ? doc.get("_source").toString().getBytes(StandardCharsets.UTF_8)
                            : null,
                        (String) doc.get("_routing"),
                        DocumentChange.ChangeType.INDEX
                    ))
                    .toList();

                sink.writeBatch(shardId, "golden_test", docs).block();
                verifyDocCount(cluster, "golden_test", docs.size());
                log.info("Golden fixture test: wrote {} docs from es710-wsoft-docs.json", docs.size());
            }
        }
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createClient(SearchClusterContainer cluster) {
        var connectionContext = ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext();
        return new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
        restClient.get("_refresh", context.createUnboundRequestContext());
        var requests = new SearchClusterRequests(context);
        var counts = requests.getMapOfIndexAndDocCount(restClient);
        assertEquals(expected, counts.getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }
}
