package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DefaultSourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.delta.DeltaLuceneReader;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;
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
            .map(SnapshotRepo.Index::getName)
            .toList();
    }

    /**
     * Lists all snapshot names in the repository.
     */
    public List<String> listSnapshots() {
        return snapshotReader.getShardMetadata()
            .getRepoDataProvider()
            .getSnapshots()
            .stream()
            .map(s -> s.getName())
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

    /**
     * Reads delta documents between a previous and current shard. Returns deletions first,
     * then additions — matching the ordering used by {@code MigrationPipeline}.
     */
    public Flux<LuceneDocumentChange> readDeltaDocuments(
        ShardEntry currentShard,
        ShardEntry previousShard,
        DeltaMode deltaMode,
        Path workDir,
        BaseRootRfsContext rootContext
    ) {
        var repoAccessor = new DefaultSourceRepoAccessor(sourceRepo);
        var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, workDir);
        var readerFactory = new LuceneIndexReader.Factory(snapshotReader);

        // Combine files from both snapshots for unpacking
        Set<ShardFileInfo> filesToUnpack = Stream.concat(
                currentShard.metadata().getFiles().stream(),
                previousShard.metadata().getFiles().stream())
            .collect(Collectors.toCollection(
                () -> new TreeSet<>(Comparator.comparing(ShardFileInfo::key))));

        var unpacker = unpackerFactory.create(
            filesToUnpack,
            currentShard.indexName(),
            currentShard.indexId(),
            currentShard.shardId()
        );
        unpacker.unpack();

        Path shardPath = workDir.resolve(currentShard.indexName())
            .resolve(String.valueOf(currentShard.shardId()));
        LuceneIndexReader indexReader = readerFactory.getReader(shardPath);

        LuceneDirectoryReader previousReader;
        LuceneDirectoryReader currentReader;
        try {
            previousReader = indexReader.getReader(previousShard.metadata().getSegmentFileName());
            currentReader = indexReader.getReader(currentShard.metadata().getSegmentFileName());
        } catch (IOException e) {
            throw new RuntimeException("Failed to open delta readers for " + currentShard, e);
        }

        DeltaLuceneReader.DeltaResult deltaResult;
        try {
            deltaResult = DeltaLuceneReader.readDeltaDocsByLeavesFromStartingPosition(
                previousReader, currentReader, 0, rootContext);
        } catch (Exception e) {
            LuceneDirectoryReader.getCleanupRunnable(previousReader, currentReader).run();
            throw new RuntimeException("Failed to compute delta for " + currentShard, e);
        }

        var deletions = switch (deltaMode) {
            case UPDATES_ONLY -> Flux.<LuceneDocumentChange>empty();
            case UPDATES_AND_DELETES, DELETES_ONLY -> deltaResult.deletions;
        };
        var additions = switch (deltaMode) {
            case DELETES_ONLY -> Flux.<LuceneDocumentChange>empty();
            case UPDATES_ONLY, UPDATES_AND_DELETES -> deltaResult.additions;
        };

        return Flux.concat(deletions, additions)
            .doFinally(s -> LuceneDirectoryReader.getCleanupRunnable(previousReader, currentReader).run());
    }

    public record ShardEntry(
        String snapshotName,
        String indexName,
        String indexId,
        int shardId,
        ShardMetadata metadata
    ) {}
}
