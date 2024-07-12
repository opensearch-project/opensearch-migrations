package com.rfs.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.lucene.document.Document;

import com.rfs.cms.IWorkCoordinator;
import com.rfs.cms.ScopedWorkCoordinator;
import com.rfs.common.DocumentReindexer;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.RfsException;
import com.rfs.common.SnapshotShardUnpacker;
import com.rfs.models.ShardMetadata;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@AllArgsConstructor
public class DocumentsRunner {
    public static final String ALL_INDEX_MANIFEST = "all_index_manifest";

    ScopedWorkCoordinator workCoordinator;
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
    public CompletionStatus migrateNextShard() throws IOException, InterruptedException {
        return workCoordinator.ensurePhaseCompletion(wc -> {
            try {
                return wc.acquireNextWorkItem(Duration.ofMinutes(10));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }, new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<>() {
            @Override
            public CompletionStatus onAlreadyCompleted() throws IOException {
                return CompletionStatus.NOTHING_DONE;
            }

            @Override
            public CompletionStatus onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException {
                doDocumentsMigration(IndexAndShard.valueFromWorkItemString(workItem.getWorkItemId()));
                return CompletionStatus.WORK_COMPLETED;
            }

            @Override
            public CompletionStatus onNoAvailableWorkToBeDone() throws IOException {
                return CompletionStatus.NOTHING_DONE;
            }
        });
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

    private void doDocumentsMigration(IndexAndShard indexAndShard) {
        log.info("Migrating docs for " + indexAndShard);
        ShardMetadata shardMetadata = shardMetadataFactory.apply(indexAndShard.indexName, indexAndShard.shard);

        var unpacker = unpackerFactory.create(shardMetadata);
        var reader = readerFactory.apply(unpacker.unpack());
        Flux<Document> documents = reader.readDocuments();

        reindexer.reindex(shardMetadata.getIndexName(), documents)
            .doOnError(error -> log.error("Error during reindexing: " + error))
            .doOnSuccess(
                done -> log.atInfo()
                    .setMessage(
                        () -> "Reindexing completed for Index "
                            + shardMetadata.getIndexName()
                            + ", Shard "
                            + shardMetadata.getShardId()
                    )
                    .log()
            )
            // Wait for the reindexing to complete before proceeding
            .block();
        log.info("Docs migrated");
    }
}
