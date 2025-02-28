package org.opensearch.migrations.bulkload.framework;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_es_7_10.ElasticsearchConstants_ES_7_10;
import org.opensearch.migrations.bulkload.version_es_7_10.IndexMetadataFactory_ES_7_10;
import org.opensearch.migrations.bulkload.version_es_7_10.ShardMetadataFactory_ES_7_10;
import org.opensearch.migrations.bulkload.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shadow.lucene9.org.apache.lucene.util.IOUtils;

/**
 * Simplified version of RFS for use in testing - ES 7.10 version.
 */
public class SimpleRestoreFromSnapshot_ES_7_10 implements SimpleRestoreFromSnapshot {

    private static final Logger logger = LogManager.getLogger(SimpleRestoreFromSnapshot_ES_7_10.class);

    public List<IndexMetadata> extractSnapshotIndexData(
        final String localPath,
        final String snapshotName,
        final Path unpackedShardDataDir
    ) throws Exception {
        IOUtils.rm(unpackedShardDataDir);

        final var repo = new FileSystemRepo(Path.of(localPath));
        SnapshotRepo.Provider snapShotProvider = new SnapshotRepoProvider_ES_7_10(repo);
        final List<IndexMetadata> indices = snapShotProvider.getIndicesInSnapshot(snapshotName).stream().map(index -> {
            try {
                return new IndexMetadataFactory_ES_7_10(snapShotProvider).fromRepo(snapshotName, index.getName());
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

        for (final IndexMetadata index : indices) {
            for (int shardId = 0; shardId < index.getNumberOfShards(); shardId++) {
                var shardMetadata = new ShardMetadataFactory_ES_7_10(snapShotProvider).fromRepo(
                    snapshotName,
                    index.getName(),
                    shardId
                );
                DefaultSourceRepoAccessor repoAccessor = new DefaultSourceRepoAccessor(repo);
                SnapshotShardUnpacker unpacker = new SnapshotShardUnpacker(
                    repoAccessor,
                    unpackedShardDataDir,
                    shardMetadata,
                    Integer.MAX_VALUE
                );
                unpacker.unpack();
            }
        }
        return indices;
    }

    @Override
    public void updateTargetCluster(
        final List<IndexMetadata> indices,
        final Path unpackedShardDataDir,
        final OpenSearchClient client,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        for (final IndexMetadata index : indices) {
            for (int shardId = 0; shardId < index.getNumberOfShards(); shardId++) {
                final var documents = new IndexReader9(
                    unpackedShardDataDir.resolve(index.getName()).resolve("" + shardId),
                    ElasticsearchConstants_ES_7_10.SOFT_DELETES_POSSIBLE,
                    ElasticsearchConstants_ES_7_10.SOFT_DELETES_FIELD
                ).readDocuments();

                final var finalShardId = shardId;
                new DocumentReindexer(client, 100, Long.MAX_VALUE, 1, () -> null).reindex(index.getName(), documents, context)
                    .then()
                    .doOnError(error -> logger.error("Error during reindexing: " + error))
                    .doOnSuccess(
                        done -> logger.info(
                            "Reindexing completed for index " + index.getName() + ", shard " + finalShardId
                        )
                    )
                    .block();
            }
        }
    }
}
