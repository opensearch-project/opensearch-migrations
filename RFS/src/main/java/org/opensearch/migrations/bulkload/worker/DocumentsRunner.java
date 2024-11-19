package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BiFunction;
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
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@AllArgsConstructor
public class DocumentsRunner {

    private final ScopedWorkCoordinator workCoordinator;
    private final Duration maxInitialLeaseDuration;
    private final BiFunction<String, Integer, ShardMetadata> shardMetadataFactory;
    private final SnapshotShardUnpacker.Factory unpackerFactory;
    private final Function<Path, LuceneDocumentsReader> readerFactory;
    private final DocumentReindexer reindexer;

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
                    return wc.acquireNextWorkItem(maxInitialLeaseDuration, context::createOpeningContext);
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
                    doDocumentsMigration(IndexAndShardCursor.valueFromWorkItemString(workItem.getWorkItemId()), context);
                    return CompletionStatus.WORK_COMPLETED;
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

    private void doDocumentsMigration(
        IndexAndShardCursor indexAndShardCursor,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        log.info("Migrating docs for " + indexAndShardCursor);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexAndShardCursor.indexName, indexAndShardCursor.shard);

        var unpacker = unpackerFactory.create(shardMetadata);
        var reader = readerFactory.apply(unpacker.unpack());
        Flux<RfsLuceneDocument> documents = reader.readDocuments(indexAndShardCursor.startingSegmentIndex, indexAndShardCursor.startingDocId);

        reindexer.reindex(shardMetadata.getIndexName(), documents, context)
            .doOnError(error -> log.error("Error during reindexing: " + error))
            .doOnSuccess(
                done -> log.atInfo().setMessage("Reindexing completed for Index {}, Shard {}")
                    .addArgument(shardMetadata::getIndexName)
                    .addArgument(shardMetadata::getShardId)
                    .log()
            )
            // Wait for the reindexing to complete before proceeding
            .block();
        log.info("Docs migrated");
    }
}
