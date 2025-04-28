package org.opensearch.migrations.bulkload;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

public class ElasticsearchSnapshotDocumentRepository extends LuceneBasedDocumentRepository {

    private final String snapshotName;
    private final SnapshotShardUnpacker.Factory unpackerFactory;
    private final ClusterSnapshotReader sourceResourceProvider;

    public ElasticsearchSnapshotDocumentRepository(String snapshotName,
                                                   ClusterSnapshotReader sourceResourceProvider,
                                                   SnapshotShardUnpacker.Factory unpackerFactory) {
        this.snapshotName = snapshotName;
        this.sourceResourceProvider = sourceResourceProvider;
        this.unpackerFactory = unpackerFactory;
    }

    public ElasticsearchSnapshotDocumentRepository(String snapshotName,
                                                   ClusterSnapshotReader sourceResourceProvider,
                                                   Function<Integer, SnapshotShardUnpacker.Factory> unpackerContinuation) {
        this(snapshotName,
            sourceResourceProvider,
            unpackerContinuation.apply(sourceResourceProvider.getBufferSizeInBytes()));
    }

    public ElasticsearchSnapshotDocumentRepository(Version sourceVersion,
                                                   String snapshotName,
                                                   Path luceneUnpackPath,
                                                   SourceRepo sourceRepo) {
        this(snapshotName,
            ClusterProviderRegistry.getSnapshotReader(sourceVersion, sourceRepo, snapshotName),
            bufferSize -> new SnapshotShardUnpacker.Factory(
                new DefaultSourceRepoAccessor(sourceRepo),
                luceneUnpackPath,
                bufferSize));
    }

    public ElasticsearchSnapshotDocumentRepository(Version sourceVersion,
                                                   String snapshotName,
                                                   Path luceneUnpackPath,
                                                   Path snapshotLocalDirPath) {
        this(sourceVersion, snapshotName, luceneUnpackPath, new FileSystemRepo(snapshotLocalDirPath));
    }

    public ElasticsearchSnapshotDocumentRepository(Version sourceVersion,
                                                   String snapshotName,
                                                   Path luceneUnpackPath,
                                                   String s3RepoUri,
                                                   String region,
                                                   Path s3localPath) {
        this(sourceVersion, snapshotName, luceneUnpackPath, S3Repo.create(s3localPath, new S3Uri(s3RepoUri), region));
    }

    @Override
    public long getShardSizeInBytes(String index, Integer shard) {
        var shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshotName, index, shard);
        return shardMetadata.getTotalSizeBytes();
    }

    @Override
    public LuceneIndexReader getReader(String index, int shard) {
        var shardMetadata = sourceResourceProvider.getShardMetadata().fromRepo(snapshotName, index, shard);
        var unpackedDir = unpackerFactory.create(shardMetadata).unpack();
        return new LuceneIndexReader.Factory(sourceResourceProvider).getReader(unpackedDir);
    }

    private IndexMetadata.Factory getIndexMetadata() {
        return sourceResourceProvider.getIndexMetadata();
    }

    @Override
    public Stream<String> getIndexNamesInSnapshot() {
        return getIndexMetadata().getRepoDataProvider().getIndicesInSnapshot().stream()
            .map(SnapshotRepo.Index::getName);
    }

    @Override
    public int getNumShards(String indexName) {
        return getIndexMetadata().fromRepo(indexName).getNumberOfShards();
    }
}
