package com.rfs.worker;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import com.rfs.cms.IWorkCoordinator;
import com.rfs.cms.ScopedWorkCoordinatorHelper;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

import com.rfs.common.DocumentReindexer;
import com.rfs.common.LuceneDocumentsReader;
import com.rfs.common.ShardMetadata;
import com.rfs.common.SnapshotShardUnpacker;
import org.apache.lucene.document.Document;
import reactor.core.publisher.Flux;

@Slf4j
@AllArgsConstructor
public class DocumentsRunner {
    public static final String ALL_INDEX_MANIFEST = "all_index_manifest";

    ScopedWorkCoordinatorHelper workCoordinator;
    private final String snapshotName;
    private final ShardMetadata.Factory shardMetadataFactory;
    private final SnapshotShardUnpacker unpacker;
    private final LuceneDocumentsReader reader;
    private final DocumentReindexer reindexer;


    public void migrateNextShard() throws IOException {
        workCoordinator.ensurePhaseCompletion(wc -> {
                    try {
                        return wc.acquireNextWorkItem(Duration.ofMinutes(10));
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                },
                new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<Void>() {
                    @Override
                    public Void onAlreadyCompleted() throws IOException {
                        return null;
                    }

                    @Override
                    public Void onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException {
                        doDocumentsMigration(IndexAndShard.valueFromWorkItemString(workItem.getWorkItemId()));
                        return null;
                    }
                });
    }

    private void doDocumentsMigration(IndexAndShard indexAndShard) throws IOException {
        log.info("Migrating docs for " + indexAndShard);
        ShardMetadata.Data shardMetadata =
                shardMetadataFactory.fromRepo(snapshotName, indexAndShard.indexName, indexAndShard.shard);
        unpacker.unpack(shardMetadata);

        Flux<Document> documents = reader.readDocuments(shardMetadata.getIndexName(), shardMetadata.getShardId());

        final int finalShardId = shardMetadata.getShardId(); // Define in local context for the lambda
        reindexer.reindex(shardMetadata.getIndexName(), documents)
                .doOnError(error -> log.error("Error during reindexing: " + error))
                .doOnSuccess(done -> log.info("Reindexing completed for Index " + shardMetadata.getIndexName() + ", Shard " + finalShardId))
                // Wait for the reindexing to complete before proceeding
                .block();
        log.info("Docs migrated");
    }
}
