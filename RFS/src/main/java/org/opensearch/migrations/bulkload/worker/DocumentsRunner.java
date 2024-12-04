package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.LuceneDocumentsReader;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
public class DocumentsRunner {

    private final ScopedWorkCoordinator workCoordinator;
    private final Duration maxInitialLeaseDuration;
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;
    private final SnapshotShardUnpacker.Factory unpackerFactory;
    private final Function<Path, LuceneDocumentsReader> readerFactory;
    private final DocumentReindexer reindexer;
    private final Consumer<WorkItemCursor> cursorConsumer;
    private final WorkItemTimeProvider timeProvider;
    private final Consumer<Runnable> cancellationTriggerConsumer;

    public DocumentsRunner(ScopedWorkCoordinator workCoordinator,
                           Duration maxInitialLeaseDuration,
                           DocumentReindexer reindexer,
                           SnapshotShardUnpacker.Factory unpackerFactory,
                           BiFunction<String, Integer, ShardMetadata> shardMetadataFactory,
                           Function<Path, LuceneDocumentsReader> readerFactory,
                           Consumer<WorkItemCursor> cursorConsumer,
                           Consumer<Runnable> cancellationTriggerConsumer,
                           WorkItemTimeProvider timeProvider) {
        this.maxInitialLeaseDuration = maxInitialLeaseDuration;
        this.readerFactory = readerFactory;
        this.reindexer = reindexer;
        this.shardMetadataFactory = shardMetadataFactory;
        this.unpackerFactory = unpackerFactory;
        this.workCoordinator = workCoordinator;
        this.cursorConsumer = cursorConsumer;
        this.cancellationTriggerConsumer = cancellationTriggerConsumer;
        this.timeProvider = timeProvider;
    }

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
                public CompletionStatus onAlreadyCompleted() throws IOException {
                    return CompletionStatus.NOTHING_DONE;
                }

                @Override
                public CompletionStatus onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) {
                    var docMigrationMono = setupDocMigration(workItem.getWorkItem(), context);
                    var latch = new CountDownLatch(1);
                    var disposable = docMigrationMono.subscribe( lastItem -> {},
                            error -> log.atError()
                                    .setCause(error)
                                    .setMessage("Error prevented all batches from being processed")
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
                    // There may be outstanding requests with newer docs that have not been fully processed
                    // and thus will show up as "deleted"/updated docs when the successor work item is processed.
                    // Consider triggering an upstream cancellation before sending requests prior to the lease expiration
                    // allowing for time to attempt to "flush out" pending requests before creating the successor items.
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

    private Mono<WorkItemCursor> setupDocMigration(
        IWorkCoordinator.WorkItemAndDuration.WorkItem workItem,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        log.atInfo().setMessage("Migrating docs for {}").addArgument(workItem).log();
        ShardMetadata shardMetadata = shardMetadataFactory.apply(workItem.getIndexName(), workItem.getShardNumber());

        var unpacker = unpackerFactory.create(shardMetadata);
        var reader = readerFactory.apply(unpacker.unpack());

        timeProvider.getDocumentMigraionStartTimeRef().set(Instant.now());

        Flux<RfsLuceneDocument> documents = reader.readDocuments(workItem.getStartingDocId());

        return reindexer.reindex(workItem.getIndexName(), documents, context)
            .doOnNext(cursorConsumer)
            .last();
    }
}
