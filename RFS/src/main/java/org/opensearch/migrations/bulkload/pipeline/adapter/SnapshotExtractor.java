package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * High-level entry point for extracting documents from Elasticsearch/OpenSearch snapshots.
 * Hides version detection, shard unpacking, and Lucene reader wiring.
 */
@Slf4j
public class SnapshotExtractor {

    @Getter
    private final Version version;
    @Getter
    private final ClusterSnapshotReader snapshotReader;
    private final SourceRepo sourceRepo;

    private SnapshotExtractor(Version version, ClusterSnapshotReader snapshotReader, SourceRepo sourceRepo) {
        this.version = version;
        this.snapshotReader = snapshotReader;
        this.sourceRepo = sourceRepo;
    }

    public static SnapshotExtractor create(Version version, ClusterSnapshotReader snapshotReader, SourceRepo sourceRepo) {
        return new SnapshotExtractor(version, snapshotReader, sourceRepo);
    }

    public static SnapshotExtractor forLocalSnapshot(Path snapshotDir, Version version) {
        var fileFinder = ClusterProviderRegistry.getSnapshotFileFinder(version, true);
        var sourceRepo = new FileSystemRepo(snapshotDir, fileFinder);
        var reader = ClusterProviderRegistry.getSnapshotReader(version, sourceRepo, true);
        return new SnapshotExtractor(version, reader, sourceRepo);
    }

    public List<String> listIndices(String snapshotName) {
        return snapshotReader.getShardMetadata()
            .getRepoDataProvider()
            .getIndicesInSnapshot(snapshotName)
            .stream()
            .map(idx -> idx.getName())
            .toList();
    }

    public List<ShardEntry> listShards(String snapshotName, String indexName) {
        var indexMetadata = snapshotReader.getIndexMetadata().fromRepo(snapshotName, indexName);
        int shardCount = indexMetadata.getNumberOfShards();

        return IntStream.range(0, shardCount)
            .mapToObj(shardId -> {
                var meta = snapshotReader.getShardMetadata().fromRepo(snapshotName, indexName, shardId);
                var indexId = snapshotReader.getShardMetadata().getRepoDataProvider().getIndexId(indexName);
                return new ShardEntry(snapshotName, indexName, indexId, shardId, meta);
            })
            .toList();
    }

    public Flux<LuceneDocumentChange> readDocuments(ShardEntry shard, Path workDir) {
        var repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
        var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, workDir);
        var readerFactory = new LuceneIndexReader.Factory(snapshotReader);

        var unpacker = unpackerFactory.create(
            new HashSet<>(shard.metadata().getFiles()),
            shard.indexName(),
            shard.indexId(),
            shard.shardId()
        );
        unpacker.unpack();

        Path shardPath = workDir.resolve(shard.indexName()).resolve(String.valueOf(shard.shardId()));
        LuceneIndexReader indexReader = readerFactory.getReader(shardPath);
        return indexReader.streamDocumentChanges(shard.metadata().getSegmentFileName());
    }

    public record ShardEntry(
        String snapshotName,
        String indexName,
        String indexId,
        int shardId,
        ShardMetadata metadata
    ) {}
}
