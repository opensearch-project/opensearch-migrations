package org.opensearch.migrations.bulkload.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.metrics.SourceExtractionMetrics;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.sink.MetadataSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceFactory;
import org.opensearch.migrations.bulkload.pipeline.source.MetadataSource;
import org.opensearch.migrations.bulkload.pipeline.source.SourceType;
import org.opensearch.migrations.bulkload.pipeline.source.SourcelessDocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.SourcelessExtractionConfig;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for rfs-pipeline that spin up real OpenSearch clusters via Testcontainers
 * and verify the pipeline writes documents correctly end-to-end.
 */
@Slf4j
@Tag("isolatedTest")
class PipelineIntegrationTest {

    /**
     * DocumentSink that writes to a real OpenSearch cluster via bulk NDJSON over HTTP.
     */
    static class HttpBulkSink implements DocumentSink {
        private final RestClient restClient;
        private final DocumentMigrationTestContext context;

        HttpBulkSink(RestClient restClient, DocumentMigrationTestContext context) {
            this.restClient = restClient;
            this.context = context;
        }

        @Override
        public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
            return Mono.fromRunnable(() -> {
                String body = String.format(
                    "{\"settings\":{\"number_of_shards\":%d,\"number_of_replicas\":0}}",
                    metadata.numberOfShards());
                var resp = restClient.put(metadata.indexName(), body, context.createUnboundRequestContext());
                // 200 = created, 400 = already exists (idempotent)
                assertTrue(resp.statusCode == 200 || resp.statusCode == 400,
                    "Index creation failed for " + metadata.indexName() + ": " + resp.statusCode);
            });
        }

        @Override
        public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
            return Mono.fromCallable(() -> {
                var sb = new StringBuilder();
                for (var doc : batch) {
                    // Action line
                    sb.append("{\"index\":{\"_index\":\"").append(indexName)
                      .append("\",\"_id\":\"").append(doc.id()).append("\"");
                    if (doc.routing() != null) {
                        sb.append(",\"routing\":\"").append(doc.routing()).append("\"");
                    }
                    sb.append("}}\n");
                    // Source line
                    if (doc.source() != null) {
                        sb.append(new String(doc.source(), StandardCharsets.UTF_8));
                    } else {
                        sb.append("{}");
                    }
                    sb.append("\n");
                }

                var resp = restClient.post(
                    "_bulk", sb.toString(), context.createUnboundRequestContext());
                assertThat("Bulk request failed", resp.statusCode, equalTo(200));

                long bytes = batch.stream().mapToLong(d -> d.source() != null ? d.source().length : 0).sum();
                return new ProgressCursor(shardId, batch.size(), batch.size(), bytes);
            });
        }
    }

    // --- Tests ---

    @Test
    void sourcelessPipelineWritesToRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();

            var config = SourcelessExtractionConfig.withDefaults("integ_test", 2, 10);
            var source = DocumentSourceFactory.select(SourceType.SOURCELESS, null, config, SourceExtractionMetrics.NOOP);
            var sink = createSink(cluster);
            var pipeline = new MigrationPipeline(source, sink, 5, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertNotNull(cursors);
            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            verifyDocCount(cluster, "integ_test", 20);
        }
    }

    @Test
    void batchingBehaviorWithRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();

            // 1 shard, 7 docs, batch size 3 → expect 3 batches (3+3+1)
            var config = SourcelessExtractionConfig.withDefaults("batch_test", 1, 7);
            var source = DocumentSourceFactory.select(SourceType.SOURCELESS, null, config, SourceExtractionMetrics.NOOP);
            var sink = createSink(cluster);
            var pipeline = new MigrationPipeline(source, sink, 3, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertNotNull(cursors);
            assertEquals(3, cursors.size(), "Should have 3 batches for 7 docs with batch size 3");
            assertEquals(3, cursors.get(0).lastDocProcessed());
            assertEquals(6, cursors.get(1).lastDocProcessed());
            assertEquals(7, cursors.get(2).lastDocProcessed());
            verifyDocCount(cluster, "batch_test", 7);
        }
    }

    @Test
    void parallelShardConcurrencyWithRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();

            var config = SourcelessExtractionConfig.withDefaults("parallel_test", 4, 15);
            var source = DocumentSourceFactory.select(SourceType.SOURCELESS, null, config, SourceExtractionMetrics.NOOP);
            var sink = createSink(cluster);
            var pipeline = new MigrationPipeline(source, sink, 10, Long.MAX_VALUE, 4);

            var cursors = pipeline.migrateAll().collectList().block();

            assertNotNull(cursors);
            verifyDocCount(cluster, "parallel_test", 60);
        }
    }

    @Test
    void metricsRecordedThroughPipeline() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();

            var parsedCount = new AtomicInteger();
            var shardStarted = new AtomicInteger();
            var shardCompleted = new AtomicInteger();
            var metrics = new SourceExtractionMetrics() {
                @Override public void recordDocumentParsed(SourceType sourceType, long bytesRead) {
                    assertEquals(SourceType.SOURCELESS, sourceType);
                    parsedCount.incrementAndGet();
                }
                @Override public void recordParseError(SourceType sourceType, String errorType) {}
                @Override public void recordBatchReadDuration(SourceType sourceType, long durationMs, int docCount) {}
                @Override public void recordShardExtractionStarted(SourceType sourceType, String indexName, int shardNumber) {
                    shardStarted.incrementAndGet();
                }
                @Override public void recordShardExtractionCompleted(SourceType sourceType, String indexName, int shardNumber, long totalDocs) {
                    shardCompleted.incrementAndGet();
                }
            };

            var config = SourcelessExtractionConfig.withDefaults("metrics_test", 2, 5);
            var source = new SourcelessDocumentSource(config, metrics);
            var sink = createSink(cluster);
            var pipeline = new MigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            pipeline.migrateAll().collectList().block();

            assertEquals(10, parsedCount.get(), "Should parse 2×5=10 docs");
            assertEquals(2, shardStarted.get(), "Should start 2 shards");
            assertEquals(2, shardCompleted.get(), "Should complete 2 shards");
            verifyDocCount(cluster, "metrics_test", 10);
        }
    }

    @Test
    void metadataThenDocumentPipeline() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();

            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var restClient = createRestClient(cluster);

            // Metadata pipeline
            var global = new GlobalMetadataSnapshot(null, null, null, List.of("meta_test"));
            var indexMeta = new IndexMetadataSnapshot("meta_test", 1, 0, null, null, null);
            var metaSource = new MetadataSource() {
                @Override public GlobalMetadataSnapshot readGlobalMetadata() { return global; }
                @Override public IndexMetadataSnapshot readIndexMetadata(String indexName) { return indexMeta; }
            };
            var metaSink = new MetadataSink() {
                @Override
                public Mono<Void> writeGlobalMetadata(GlobalMetadataSnapshot metadata) {
                    return Mono.empty();
                }
                @Override
                public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
                    return Mono.fromRunnable(() -> {
                        var resp = restClient.put(metadata.indexName(),
                            "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}",
                            context.createUnboundRequestContext());
                        assertThat("Index creation failed", resp.statusCode, equalTo(200));
                    });
                }
            };

            StepVerifier.create(new MetadataMigrationPipeline(metaSource, metaSink).migrateAll())
                .expectNext("meta_test")
                .verifyComplete();

            // Verify index exists
            var resp = restClient.get("meta_test", context.createUnboundRequestContext());
            assertThat("Index should exist", resp.statusCode, equalTo(200));

            // Document pipeline writes to the created index
            var config = SourcelessExtractionConfig.withDefaults("meta_test", 1, 5);
            var docSource = DocumentSourceFactory.select(SourceType.SOURCELESS, null, config, SourceExtractionMetrics.NOOP);
            var docPipeline = new MigrationPipeline(docSource, createSink(cluster), 100, Long.MAX_VALUE);

            docPipeline.migrateAll().collectList().block();
            verifyDocCount(cluster, "meta_test", 5);
        }
    }

    @Test
    void byteSizeBatchingWithRealCluster() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();

            // 1KB docs, 2KB byte limit → ~2 docs per batch → multiple batches for 5 docs
            var config = new SourcelessExtractionConfig("bytesize_test", 1, 5, 1024);
            var source = DocumentSourceFactory.select(SourceType.SOURCELESS, null, config, SourceExtractionMetrics.NOOP);
            var sink = createSink(cluster);
            var pipeline = new MigrationPipeline(source, sink, Integer.MAX_VALUE, 2048);

            var cursors = pipeline.migrateAll().collectList().block();

            assertNotNull(cursors);
            assertThat("Should have multiple batches from byte-size limiting", cursors.size(), greaterThan(1));
            verifyDocCount(cluster, "bytesize_test", 5);
        }
    }

    // --- Helpers ---

    private HttpBulkSink createSink(SearchClusterContainer cluster) {
        var restClient = createRestClient(cluster);
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        return new HttpBulkSink(restClient, context);
    }

    private RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
    }

    private void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
        restClient.get("_refresh", context.createUnboundRequestContext());
        var requests = new SearchClusterRequests(context);
        var counts = requests.getMapOfIndexAndDocCount(restClient);
        assertEquals(expected, counts.getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }
}
