package org.opensearch.migrations.bulkload.pipeline;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.dlq.DlqSink;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * Top-level wiring for the clean pipeline approach. Connects all adapters and provides
 * work-coordinated execution for horizontal scaling.
 *
 * <pre>
 * var runner = DocumentMigrationBootstrap.builder()
 *     .extractor(snapshotExtractor)
 *     .targetClient(openSearchClient)
 *     .snapshotName("my-snapshot")
 *     .workDir(Paths.get("/tmp/lucene"))
 *     .workCoordinator(scopedWorkCoordinator)
 *     .maxInitialLeaseDuration(Duration.ofMinutes(10))
 *     .cursorConsumer(progressCursor::set)
 *     .cancellationTriggerConsumer(cancellationRef::set)
 *     .build();
 * runner.migrateOneShard(contextSupplier);
 * </pre>
 */
@Slf4j
@Builder
@SuppressWarnings("java:S1170") // Builder.Default fields are instance-level, not static
public class DocumentMigrationBootstrap {

    private final DocumentSource documentSource;
    private final OpenSearchClient targetClient;

    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final int batchConcurrency;

    @Builder.Default
    private final Supplier<IJsonTransformer> transformerSupplier = null;
    @Builder.Default
    private final boolean allowServerGeneratedIds = false;
    @Builder.Default
    private final DocumentExceptionAllowlist allowlist = DocumentExceptionAllowlist.empty();

    @Builder.Default
    private final ScopedWorkCoordinator workCoordinator = null;
    @Builder.Default
    private final WorkItemTimeProvider workItemTimeProvider = null;
    @Builder.Default
    private final Duration maxInitialLeaseDuration = Duration.ofMinutes(10);
    @Builder.Default
    private final Consumer<WorkItemCursor> cursorConsumer = cursor -> {};
    @Builder.Default
    private final Consumer<Runnable> cancellationTriggerConsumer = runnable -> {};

    /**
     * Acquire and migrate a single shard via work coordination.
     * Each JVM invocation processes one work item, then exits so the
     * orchestrator can restart the process for the next shard.
     *
     * @return WORK_COMPLETED if a shard was migrated, NOTHING_DONE if no work was available
     */
    public CompletionStatus migrateOneShard(
        Supplier<IDocumentMigrationContexts.IDocumentReindexContext> contextSupplier
    ) throws IOException, InterruptedException {
        if (workCoordinator == null) {
            throw new IllegalStateException("workCoordinator must be set for coordinated migration");
        }
        var source = documentSource;
        var contextRef = new AtomicReference<IDocumentMigrationContexts.IDocumentReindexContext>();
        var sink = new OpenSearchDocumentSink(
            targetClient, transformerSupplier, allowServerGeneratedIds, allowlist,
            () -> {
                var ctx = contextRef.get();
                return ctx != null ? ctx.createBulkRequest() : null;
            }
        );
        try {
            var pipelineConfig = new PipelineConfig(source, sink, maxDocsPerBatch, maxBytesPerBatch, batchConcurrency);
            Supplier<IDocumentMigrationContexts.IDocumentReindexContext> wrappedContextSupplier = () -> {
                var ctx = contextSupplier.get();
                contextRef.set(ctx);
                return ctx;
            };
            try (var context = wrappedContextSupplier.get()) {
                return workCoordinator.ensurePhaseCompletion(wc -> {
                    try {
                        return wc.acquireNextWorkItem(maxInitialLeaseDuration, context::createOpeningContext);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<>() {
                    @Override
                    public CompletionStatus onAlreadyCompleted() {
                        return CompletionStatus.NOTHING_DONE;
                    }

                    @Override
                    public CompletionStatus onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) {
                        return runPartitionMigration(workItem, pipelineConfig, context);
                    }

                    @Override
                    public CompletionStatus onNoAvailableWorkToBeDone() {
                        return CompletionStatus.NOTHING_DONE;
                    }
                }, context::createCloseContext);
            }
        } finally {
            closeQuietly(source);
            closeQuietly(sink);
        }
    }

    private CompletionStatus runPartitionMigration(
        IWorkCoordinator.WorkItemAndDuration workItem,
        PipelineConfig pipelineConfig,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        var wi = workItem.getWorkItem();
        log.info("Pipeline acquired work item: {}", wi);

        if (workItemTimeProvider != null) {
            workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.now());
        }

        var partition = resolvePartition(wi);
        long startingOffset = wi.getStartingDocId() != null && wi.getStartingDocId() >= 0
            ? wi.getStartingDocId() : 0;

        var pipeline = new DocumentMigrationPipeline(
            pipelineConfig.source(), pipelineConfig.sink(),
            pipelineConfig.maxDocsPerBatch(), pipelineConfig.maxBytesPerBatch(),
            1, pipelineConfig.batchConcurrency()
        );
        var progressMonitor = new PipelineProgressMonitor(pipeline);
        progressMonitor.start();
        var latch = new CountDownLatch(1);
        var batchCount = new AtomicInteger();
        var totalDocsMigrated = new AtomicLong();
        var totalBytesMigrated = new AtomicLong();
        var migrationError = new AtomicReference<Throwable>();
        var finishScheduler = Schedulers.newSingle("pipelineFinishScheduler");

        var disposable = pipeline.migratePartition(partition, wi.getIndexName(), startingOffset)
            .subscribeOn(finishScheduler)
            .doFirst(() -> {
                if (workItemTimeProvider != null) {
                    workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.now());
                }
            })
            .doFinally(s -> finishScheduler.dispose())
            .subscribe(
                cursor -> {
                    batchCount.incrementAndGet();
                    totalDocsMigrated.addAndGet(cursor.docsInBatch());
                    totalBytesMigrated.addAndGet(cursor.bytesInBatch());
                    cursorConsumer.accept(new WorkItemCursor(cursor.lastDocProcessed()));
                },
                error -> {
                    log.atError()
                        .setCause(error)
                        .setMessage("Pipeline error for {}")
                        .addArgument(wi)
                        .log();
                    migrationError.set(error);
                    latch.countDown();
                },
                () -> {
                    log.info("Pipeline completed for index={}, shard={}", wi.getIndexName(), wi.getShardNumber());
                    latch.countDown();
                }
            );

        cancellationTriggerConsumer.accept(disposable::dispose);

        try {
            long start = System.currentTimeMillis();
            latch.await(); // Bridge reactive→sync: block until pipeline subscription completes
            long durationMs = System.currentTimeMillis() - start;
            log.atInfo()
                .setMessage("Partition migration stats: index={}, shard={}, docs={}, bytes={}, batches={}, duration={}ms")
                .addArgument(wi.getIndexName())
                .addArgument(wi.getShardNumber())
                .addArgument(totalDocsMigrated::get)
                .addArgument(totalBytesMigrated::get)
                .addArgument(batchCount::get)
                .addArgument(durationMs)
                .log();

            var error = migrationError.get();
            if (error != null) {
                context.recordPipelineError();
                throw new RfsException("Partition migration failed for " + wi, error);
            }

            // Flush the DLQ before letting ScopedWorkCoordinator call completeWorkItem.
            // If the flush fails (or times out), we throw so the work item is NOT marked
            // complete — a successor worker can re-emit any failures we may have lost.
            // This is the contract behind the DLQ lease-lifecycle requirement.
            flushDlqOrThrow(targetClient.getDlqSink(), wi);

            context.recordShardDuration(durationMs);
            context.recordDocsMigrated(totalDocsMigrated.get());
            context.recordBytesMigrated(totalBytesMigrated.get());
            return CompletionStatus.WORK_COMPLETED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RfsException("Partition migration interrupted", e);
        } finally {
            progressMonitor.close();
        }
    }

    static void flushDlqOrThrow(DlqSink dlqSink, IWorkCoordinator.WorkItemAndDuration.WorkItem wi) {
        if (dlqSink == null) {
            return;
        }
        try {
            dlqSink.flush().block(Duration.ofMinutes(5));
        } catch (Exception flushError) {
            log.atError().setCause(flushError)
                .setMessage("DLQ flush failed for {}; refusing to mark work item complete")
                .addArgument(wi).log();
            throw new RfsException("DLQ flush failed for " + wi, flushError);
        }
    }

    private org.opensearch.migrations.bulkload.pipeline.model.Partition resolvePartition(
            IWorkCoordinator.WorkItemAndDuration.WorkItem wi) {
        var partitions = documentSource.listPartitions(wi.getIndexName());
        int shardIdx = wi.getShardNumber();
        if (shardIdx < partitions.size()) {
            return partitions.get(shardIdx);
        }
        throw new IllegalStateException("Shard index " + shardIdx + " out of range for " +
            wi.getIndexName() + " (has " + partitions.size() + " partitions)");
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Error closing resource", e);
        }
    }
}
