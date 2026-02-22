package org.opensearch.migrations.bulkload.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchMetadataSink;
import org.opensearch.migrations.bulkload.pipeline.adapter.SnapshotMetadataSource;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Top-level wiring for the clean pipeline approach. Connects all adapters and provides
 * both standalone and work-coordinated execution modes.
 *
 * <h3>Standalone mode</h3>
 * <pre>
 * var runner = PipelineRunner.builder()
 *     .extractor(snapshotExtractor)
 *     .targetClient(openSearchClient)
 *     .snapshotName("my-snapshot")
 *     .workDir(Paths.get("/tmp/lucene"))
 *     .build();
 * runner.migrateDocuments().blockLast();
 * </pre>
 *
 * <h3>Work-coordinated mode</h3>
 * <pre>
 * var runner = PipelineRunner.builder()
 *     .extractor(snapshotExtractor)
 *     .targetClient(openSearchClient)
 *     .snapshotName("my-snapshot")
 *     .workDir(Paths.get("/tmp/lucene"))
 *     .workCoordinator(scopedWorkCoordinator)
 *     .maxInitialLeaseDuration(Duration.ofMinutes(10))
 *     .cursorConsumer(progressCursor::set)
 *     .cancellationTriggerConsumer(cancellationRef::set)
 *     .build();
 * runner.migrateNextShard(contextSupplier);
 * </pre>
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

    // Optional: document transformation
    @Builder.Default
    private final Supplier<IJsonTransformer> transformerSupplier = null;
    @Builder.Default
    private final boolean allowServerGeneratedIds = false;
    @Builder.Default
    private final DocumentExceptionAllowlist allowlist = DocumentExceptionAllowlist.empty();

    // Optional: work coordination
    @Builder.Default
    private final ScopedWorkCoordinator workCoordinator = null;
    @Builder.Default
    private final Duration maxInitialLeaseDuration = Duration.ofMinutes(10);
    @Builder.Default
    private final Consumer<WorkItemCursor> cursorConsumer = cursor -> {};
    @Builder.Default
    private final Consumer<Runnable> cancellationTriggerConsumer = runnable -> {};

    /**
     * Migrate all documents from the snapshot to the target cluster (standalone mode).
     */
    public Flux<ProgressCursor> migrateDocuments() {
        var source = createDocumentSource();
        var sink = createDocumentSink();
        var pipeline = new MigrationPipeline(source, sink, maxDocsPerBatch, maxBytesPerBatch, shardConcurrency);

        log.info("Starting pipeline migration: snapshot={}, concurrency={}", snapshotName, shardConcurrency);
        return pipeline.migrateAll()
            .doOnComplete(() -> {
                log.info("Pipeline migration complete");
                closeQuietly(source);
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

    /**
     * Acquire the next available shard via work coordination and migrate it (coordinated mode).
     * Requires {@code workCoordinator} to be set.
     *
     * @return completion status indicating whether work was done
     */
    public PipelineDocumentsRunner.CompletionStatus migrateNextShard(
        Supplier<IDocumentMigrationContexts.IDocumentReindexContext> contextSupplier
    ) throws java.io.IOException, InterruptedException {
        if (workCoordinator == null) {
            throw new IllegalStateException("workCoordinator must be set for coordinated migration");
        }
        var source = createDocumentSource();
        var sink = createDocumentSink();
        var runner = new PipelineDocumentsRunner(
            workCoordinator,
            maxInitialLeaseDuration,
            source,
            sink,
            maxDocsPerBatch,
            maxBytesPerBatch,
            snapshotName,
            cursorConsumer,
            cancellationTriggerConsumer
        );
        return runner.migrateNextShard(contextSupplier);
    }

    private LuceneSnapshotSource createDocumentSource() {
        return new LuceneSnapshotSource(extractor, snapshotName, workDir);
    }

    private OpenSearchDocumentSink createDocumentSink() {
        return new OpenSearchDocumentSink(targetClient, transformerSupplier, allowServerGeneratedIds, allowlist);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Error closing resource", e);
        }
    }
}
