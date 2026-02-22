package org.opensearch.migrations.bulkload.pipeline;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchMetadataSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.SnapshotMetadataSource;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Wires the clean pipeline with real adapters to perform a full snapshot-to-OpenSearch migration.
 *
 * <p>This is the top-level entry point that replaces the existing
 * {@code DocumentsRunner + DocumentReindexer} flow with the clean pipeline approach.
 *
 * <p>Usage:
 * <pre>{@code
 * var runner = PipelineRunner.builder()
 *     .extractor(snapshotExtractor)
 *     .targetClient(openSearchClient)
 *     .snapshotName("my-snapshot")
 *     .workDir(Paths.get("/tmp/lucene"))
 *     .maxDocsPerBatch(1000)
 *     .maxBytesPerBatch(10_000_000)
 *     .shardConcurrency(4)
 *     .build();
 *
 * runner.migrateDocuments().blockLast();
 * }</pre>
 */
@Slf4j
@Builder
@SuppressWarnings("java:S1170") // Builder.Default fields are instance-level, not static
public class PipelineRunner {

    private final SnapshotExtractor extractor;
    private final OpenSearchClient targetClient;
    private final String snapshotName;
    private final Path workDir;

    @Builder.Default
    private final int maxDocsPerBatch = 1000;
    @Builder.Default
    private final long maxBytesPerBatch = 10_000_000L;
    @Builder.Default
    private final int shardConcurrency = 1;

    /**
     * Migrate all documents from the snapshot to the target cluster.
     */
    public Flux<ProgressCursor> migrateDocuments() {
        var source = new LuceneSnapshotSource(extractor, snapshotName, workDir);
        var sink = new OpenSearchDocumentSink(targetClient);
        var pipeline = new MigrationPipeline(source, sink, maxDocsPerBatch, maxBytesPerBatch, shardConcurrency);

        log.info("Starting pipeline migration: snapshot={}, concurrency={}", snapshotName, shardConcurrency);
        return pipeline.migrateAll()
            .doOnComplete(() -> {
                log.info("Pipeline migration complete");
                try {
                    source.close();
                } catch (Exception e) {
                    log.warn("Error closing source", e);
                }
            });
    }

    /**
     * Migrate metadata (global templates + index creation) from the snapshot to the target cluster.
     */
    public Flux<String> migrateMetadata() {
        var source = new SnapshotMetadataSource(extractor, snapshotName);
        var sink = new OpenSearchMetadataSink(targetClient);
        var pipeline = new MetadataMigrationPipeline(source, sink);

        log.info("Starting metadata migration: snapshot={}", snapshotName);
        return pipeline.migrateAll();
    }
}
