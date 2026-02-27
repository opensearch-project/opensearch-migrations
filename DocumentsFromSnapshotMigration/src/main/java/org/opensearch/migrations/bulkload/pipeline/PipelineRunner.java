package org.opensearch.migrations.bulkload.pipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Top-level wiring for the clean pipeline approach. Connects all adapters and provides
 * work-coordinated execution for horizontal scaling.
 *
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

    // Optional: document transformation
    @Builder.Default
    private final Supplier<IJsonTransformer> transformerSupplier = null;
    @Builder.Default
    private final boolean allowServerGeneratedIds = false;
    @Builder.Default
    private final DocumentExceptionAllowlist allowlist = DocumentExceptionAllowlist.empty();

    // Optional: delta snapshot support
    @Builder.Default
    private final String previousSnapshotName = null;
    @Builder.Default
    private final DeltaMode deltaMode = null;
    @Builder.Default
    private final Supplier<IRfsContexts.IDeltaStreamContext> deltaContextFactory = null;

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
     * Acquire the next available shard via work coordination and migrate it (coordinated mode).
     * Requires {@code workCoordinator} to be set.
     *
     * @return completion status indicating whether work was done
     */
    public CompletionStatus migrateNextShard(
        Supplier<IDocumentMigrationContexts.IDocumentReindexContext> contextSupplier
    ) throws java.io.IOException, InterruptedException {
        if (workCoordinator == null) {
            throw new IllegalStateException("workCoordinator must be set for coordinated migration");
        }
        var source = createDocumentSource();
        // Bridge: capture the context reference so the sink can create per-batch request contexts
        // for HTTP-level metrics (bytes sent/read, request count/duration)
        var contextRef = new AtomicReference<IDocumentMigrationContexts.IDocumentReindexContext>();
        var sink = new OpenSearchDocumentSink(
            targetClient, transformerSupplier, allowServerGeneratedIds, allowlist,
            () -> {
                var ctx = contextRef.get();
                return ctx != null ? ctx.createBulkRequest() : null;
            }
        );
        try {
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
            return runner.migrateNextShard(() -> {
                var ctx = contextSupplier.get();
                contextRef.set(ctx);
                return ctx;
            });
        } finally {
            closeQuietly(source);
            closeQuietly(sink);
        }
    }

    private LuceneSnapshotSource createDocumentSource() {
        if (previousSnapshotName != null && deltaMode != null) {
            log.info("Creating delta document source: previous={}, mode={}", previousSnapshotName, deltaMode);
            return new LuceneSnapshotSource(
                extractor, snapshotName, workDir,
                previousSnapshotName, deltaMode, deltaContextFactory
            );
        }
        return new LuceneSnapshotSource(extractor, snapshotName, workDir);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Error closing resource", e);
        }
    }
}
