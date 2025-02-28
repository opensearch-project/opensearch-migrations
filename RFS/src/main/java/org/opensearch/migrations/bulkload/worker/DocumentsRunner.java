package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Slf4j
@AllArgsConstructor
public class DocumentsRunner {
    private final ScopedWorkCoordinator workCoordinator;
    private final Duration maxInitialLeaseDuration;
    private final DocumentReindexer reindexer;
    private final SnapshotShardUnpacker.Factory unpackerFactory;
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;
    private final LuceneIndexReader.Factory readerFactory;
    private final Consumer<WorkItemCursor> cursorConsumer;
    private final Consumer<Runnable> cancellationTriggerConsumer;
    private final WorkItemTimeProvider timeProvider;

    public enum CompletionStatus {
        NOTHING_DONE,
        WORK_COMPLETED
    }

    /**
     * @return true if it did work, false if there was no available work at this time.
     * @throws IOException
     */
    public CompletionStatus migrateNextShard(
        Supplier<IDocumentMigrationContexts.IDocumentReindexContext> contextSupplier
    ) throws IOException, InterruptedException {
        try (var context = contextSupplier.get()) {
            return workCoordinator.ensurePhaseCompletion(wc -> {
                try {
                    var workAcquisitionOutcome = wc.acquireNextWorkItem(maxInitialLeaseDuration, context::createOpeningContext);
                    timeProvider.getLeaseAcquisitionTimeRef().set(Instant.now());
                    return workAcquisitionOutcome;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw Lombok.sneakyThrow(e);
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            }, new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<>() {
                @Override
                public CompletionStatus onAlreadyCompleted() {
                    return CompletionStatus.NOTHING_DONE;
                }

                @Override
                public CompletionStatus onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) {
                    var docMigrationCursors = setupDocMigration(workItem.getWorkItem(), context);
                    var latch = new CountDownLatch(1);
                    var finishScheduler = Schedulers.newSingle( "workFinishScheduler");
                    var disposable = docMigrationCursors
                        .subscribeOn(finishScheduler)
                        .doFinally(s -> finishScheduler.dispose())
                        .takeLast(1)
                        .subscribe(lastItem -> {},
                            error -> log.atError()
                                    .setCause(error)
                                    .setMessage("Error prevented some batches from being processed")
                                    .log(),
                            () ->  {
                                log.atInfo().setMessage("Reindexing completed for Index {}, Shard {}")
                                        .addArgument(workItem.getWorkItem().getIndexName())
                                        .addArgument(workItem.getWorkItem().getShardNumber())
                                        .log();
                                latch.countDown();
                            });
                    // This allows us to cancel the subscription to stop sending new docs
                    // when the lease expires and a successor work item is made.
                    // There may be in-flight requests that are not reflected in the progress cursor
                    // and thus will be sent again during the successor work item.
                    // These will count as "deleted" from a lucene perspective and show up as "deletedDocs" during cat-indices
                    // However, the target active state will remain consistent with the snapshot and will get cleaned
                    // up during lucene segment merges.
                    //
                    // To reduce the docs processed more than once, consider triggering an upstream cancellation
                    // before sending requests prior to the lease expiration allowing
                    // the in-flight requests to be finished before creating the successor items.
                    cancellationTriggerConsumer.accept(disposable::dispose);
                    try {
                        latch.await();
                        return CompletionStatus.WORK_COMPLETED;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw Lombok.sneakyThrow(e);
                    }
                }

                @Override
                public CompletionStatus onNoAvailableWorkToBeDone() throws IOException {
                    return CompletionStatus.NOTHING_DONE;
                }
            }, context::createCloseContet);
        }
    }

    public static class ShardTooLargeException extends RfsException {
        public ShardTooLargeException(long shardSizeBytes, long maxShardSize) {
            super(
                "The shard size of "
                    + shardSizeBytes
                    + " bytes exceeds the maximum shard size of "
                    + maxShardSize
                    + " bytes"
            );
        }
    }

    private Flux<WorkItemCursor> setupDocMigration(
        IWorkCoordinator.WorkItemAndDuration.WorkItem workItem,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        log.atInfo().setMessage("Migrating docs for {}").addArgument(workItem).log();
        ShardMetadata shardMetadata = shardMetadataFactory.apply(workItem.getIndexName(), workItem.getShardNumber());

        var unpacker = unpackerFactory.create(shardMetadata);
        var reader = readerFactory.getReader(unpacker.unpack());
        timeProvider.getDocumentMigraionStartTimeRef().set(Instant.now());

        Flux<RfsLuceneDocument> documents = reader.readDocuments(workItem.getStartingDocId());

        return reindexer.reindex(workItem.getIndexName(), documents, context)
            .doOnNext(cursorConsumer);
    }
}
