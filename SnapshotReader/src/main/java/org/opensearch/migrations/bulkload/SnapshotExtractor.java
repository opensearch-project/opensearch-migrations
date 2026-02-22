package org.opensearch.migrations.bulkload;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.SourceRepoAccessor;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import lombok.Getter;
import reactor.core.publisher.Flux;

/**
 * High-level entry point for extracting documents from Elasticsearch/OpenSearch snapshots.
 * Hides version detection, shard unpacking, and Lucene reader wiring.
 *
 * <pre>{@code
 * var extractor = SnapshotExtractor.forLocalSnapshot(Path.of("/snapshots"), version);
 * var shards = extractor.listShards("my-snapshot", "my-index");
 * for (var shard : shards) {
 *     Flux<LuceneDocumentChange> docs = extractor.readDocuments(shard, workDir);
 *     docs.subscribe(doc -> ...);
 * }
 * }</pre>
 */
public class SnapshotExtractor {

    @Getter
    private final Version version;
    private final ClusterSnapshotReader snapshotReader;
    private final SourceRepo sourceRepo;

    private SnapshotExtractor(Version version, ClusterSnapshotReader snapshotReader, SourceRepo sourceRepo) {
        this.version = version;
        this.snapshotReader = snapshotReader;
        this.sourceRepo = sourceRepo;
    }

    // Visible for testing
    static SnapshotExtractor create(Version version, ClusterSnapshotReader snapshotReader, SourceRepo sourceRepo) {
        return new SnapshotExtractor(version, snapshotReader, sourceRepo);
    }

    /**
     * Creates a SnapshotExtractor for a local snapshot directory with a known version.
     */
    public static SnapshotExtractor forLocalSnapshot(Path snapshotDir, Version version) {
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        var sourceRepo = new FileSystemRepo(snapshotDir, fileFinder);
        var reader = SnapshotReaderRegistry.getSnapshotReader(version, sourceRepo, true);
        return new SnapshotExtractor(version, reader, sourceRepo);
    }

    /**
     * Lists all shards for a given index in the snapshot.
     */
    public List<ShardEntry> listShards(String snapshotName, String indexName) {
        var repoDataProvider = snapshotReader.getShardMetadata().getRepoDataProvider();
        var indexId = repoDataProvider.getIndexId(indexName);

        // Get shard count from index metadata
        var indexMetadata = snapshotReader.getIndexMetadata().fromRepo(snapshotName, indexName);
        int shardCount = indexMetadata.getNumberOfShards();

        return IntStream.range(0, shardCount)
            .mapToObj(shardId -> {
                var meta = snapshotReader.getShardMetadata().fromRepo(snapshotName, indexName, shardId);
                return new ShardEntry(snapshotName, indexName, indexId, shardId, meta);
            })
            .toList();
    }

    /**
     * Lists all index names in the given snapshot.
     */
    public List<String> listIndices(String snapshotName) {
        return snapshotReader.getShardMetadata()
            .getRepoDataProvider()
            .getIndicesInSnapshot(snapshotName)
            .stream()
            .map(idx -> idx.getName())
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

    /**
     * Reads all documents from a shard entry. Unpacks shard files to workDir first.
     *
     * @param shard   the shard to read (from {@link #listShards})
     * @param workDir temporary directory for unpacked Lucene files
     * @return a Flux of document changes (additions and deletions)
     */
    public Flux<LuceneDocumentChange> readDocuments(ShardEntry shard, Path workDir) {
        var repoAccessor = new SourceRepoAccessor(sourceRepo);
        var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, workDir);
        var readerFactory = new LuceneIndexReader.Factory(snapshotReader);

        // Unpack shard files
        var unpacker = unpackerFactory.create(
            new HashSet<>(shard.metadata().getFiles()),
            shard.indexName(),
            shard.indexId(),
            shard.shardId()
        );
        unpacker.unpack();

        // Read documents
        Path shardPath = workDir.resolve(shard.indexName()).resolve(String.valueOf(shard.shardId()));
        LuceneIndexReader indexReader = readerFactory.getReader(shardPath);
        return indexReader.streamDocumentChanges(shard.metadata().getSegmentFileName());
    }

    /**
     * Provides access to the underlying ClusterSnapshotReader for advanced use cases.
     */
    public ClusterSnapshotReader getSnapshotReader() {
        return snapshotReader;
    }

    /**
     * Represents a shard within a snapshot index.
     */
    @SuppressWarnings({"java:S100", "java:S1186"})
    public record ShardEntry(
        String snapshotName,
        String indexName,
        String indexId,
        int shardId,
        ShardMetadata metadata
    ) {}
}
