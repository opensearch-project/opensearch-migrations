package org.opensearch.migrations.bulkload.pipeline;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * Work-coordination-aware document migration using the clean pipeline.
 *
 * <p>This is the pipeline equivalent of {@link org.opensearch.migrations.bulkload.worker.DocumentsRunner}.
 * It acquires work items (shards) via {@link ScopedWorkCoordinator}, processes them using
 * {@link MigrationPipeline#migrateShard}, and reports progress via {@link WorkItemCursor}.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Lease-based shard assignment — only processes shards it has acquired a lease for</li>
 *   <li>Progress tracking — emits {@link WorkItemCursor} for each batch, enabling successor work items</li>
 *   <li>Cancellation — supports cancellation on lease expiry via the cancellation trigger</li>
 *   <li>Resumability — uses {@code startingDocId} from work items to resume mid-shard</li>
 * </ul>
 */
@Slf4j
@AllArgsConstructor
public class PipelineDocumentsRunner {

    private final ScopedWorkCoordinator workCoordinator;
    private final Duration maxInitialLeaseDuration;
    private final DocumentSource source;
    private final DocumentSink sink;
    private final int maxDocsPerBatch;
    private final long maxBytesPerBatch;
    private final String snapshotName;
    private final Consumer<WorkItemCursor> cursorConsumer;
    private final Consumer<Runnable> cancellationTriggerConsumer;

    /**
     * Acquire the next available shard and migrate its documents using the pipeline.
     *
     * @return WORK_COMPLETED if a shard was migrated, NOTHING_DONE if no work was available
     */
    public CompletionStatus migrateNextShard(
        java.util.function.Supplier<IDocumentMigrationContexts.IDocumentReindexContext> contextSupplier
    ) throws IOException, InterruptedException {
        try (var context = contextSupplier.get()) {
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
                    var wi = workItem.getWorkItem();
                    log.info("Pipeline acquired work item: {}", wi);

                    var shardId = new ShardId(snapshotName, wi.getIndexName(), wi.getShardNumber());
                    long startingOffset = wi.getStartingDocId() != null && wi.getStartingDocId() >= 0
                        ? wi.getStartingDocId() : 0;

                    var pipeline = new MigrationPipeline(source, sink, maxDocsPerBatch, maxBytesPerBatch);
                    var latch = new CountDownLatch(1);
                    var lastCursor = new AtomicReference<ProgressCursor>();
                    var batchCount = new AtomicInteger();
                    var totalDocsMigrated = new AtomicLong();
                    var totalBytesMigrated = new AtomicLong();
                    var migrationError = new AtomicReference<Throwable>();
                    var finishScheduler = Schedulers.newSingle("pipelineFinishScheduler");

                    var disposable = pipeline.migrateShard(shardId, wi.getIndexName(), startingOffset)
                        .subscribeOn(finishScheduler)
                        .doFinally(s -> finishScheduler.dispose())
                        .subscribe(
                            cursor -> {
                                lastCursor.set(cursor);
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
                        latch.await();
                        long durationMs = System.currentTimeMillis() - start;
                        log.atInfo()
                            .setMessage("Shard migration stats: index={}, shard={}, docs={}, bytes={}, batches={}, duration={}ms")
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
                            throw new RuntimeException("Shard migration failed for " + wi, error);
                        }
                        context.recordShardDuration(durationMs);
                        context.recordDocsMigrated(totalDocsMigrated.get());
                        context.recordBytesMigrated(totalBytesMigrated.get());
                        return CompletionStatus.WORK_COMPLETED;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public CompletionStatus onNoAvailableWorkToBeDone() {
                    return CompletionStatus.NOTHING_DONE;
                }
            }, context::createCloseContext);
        }
    }
}
