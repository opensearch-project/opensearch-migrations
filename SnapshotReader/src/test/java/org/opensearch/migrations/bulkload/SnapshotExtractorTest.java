package org.opensearch.migrations.bulkload;

import java.util.List;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotExtractorTest {

    @Mock ClusterSnapshotReader snapshotReader;
    @Mock SourceRepo sourceRepo;
    @Mock ShardMetadata.Factory shardMetadataFactory;
    @Mock IndexMetadata.Factory indexMetadataFactory;
    @Mock SnapshotRepo.Provider repoDataProvider;

    private SnapshotExtractor createExtractor() {
        when(snapshotReader.getShardMetadata()).thenReturn(shardMetadataFactory);
        when(snapshotReader.getIndexMetadata()).thenReturn(indexMetadataFactory);
        when(shardMetadataFactory.getRepoDataProvider()).thenReturn(repoDataProvider);
        return SnapshotExtractor.create(Version.fromString("ES 7.10"), snapshotReader, sourceRepo);
    }

    @Test
    void version_isPreserved() {
        var extractor = createExtractor();
        assertEquals(Version.fromString("ES 7.10"), extractor.getVersion());
    }

    @Test
    void listSnapshots_delegatesToProvider() {
        var extractor = createExtractor();
        var snap = mock(SnapshotRepo.Snapshot.class);
        when(snap.getName()).thenReturn("snap-1");
        when(repoDataProvider.getSnapshots()).thenReturn(List.of(snap));

        assertEquals(List.of("snap-1"), extractor.listSnapshots());
    }

    @Test
    void listIndices_delegatesToProvider() {
        var extractor = createExtractor();
        var idx = mock(SnapshotRepo.Index.class);
        when(idx.getName()).thenReturn("my-index");
        doReturn(List.of(idx)).when(repoDataProvider).getIndicesInSnapshot("snap-1");

        assertEquals(List.of("my-index"), extractor.listIndices("snap-1"));
    }

    @Test
    void listShards_usesIndexMetadataForShardCount() {
        var extractor = createExtractor();
        var indexMeta = mock(IndexMetadata.class);
        when(indexMeta.getNumberOfShards()).thenReturn(3);
        when(indexMetadataFactory.fromRepo("snap-1", "my-index")).thenReturn(indexMeta);
        when(repoDataProvider.getIndexId("my-index")).thenReturn("idx-uuid");

        var shardMeta0 = mock(ShardMetadata.class);
        var shardMeta1 = mock(ShardMetadata.class);
        var shardMeta2 = mock(ShardMetadata.class);
        when(shardMetadataFactory.fromRepo("snap-1", "my-index", 0)).thenReturn(shardMeta0);
        when(shardMetadataFactory.fromRepo("snap-1", "my-index", 1)).thenReturn(shardMeta1);
        when(shardMetadataFactory.fromRepo("snap-1", "my-index", 2)).thenReturn(shardMeta2);

        var shards = extractor.listShards("snap-1", "my-index");

        assertEquals(3, shards.size());
        assertEquals(0, shards.get(0).shardId());
        assertEquals(1, shards.get(1).shardId());
        assertEquals(2, shards.get(2).shardId());
        assertEquals("my-index", shards.get(0).indexName());
        assertEquals("idx-uuid", shards.get(0).indexId());
        assertEquals("snap-1", shards.get(0).snapshotName());
    }

    @Test
    void listShards_singleShard() {
        var extractor = createExtractor();
        var indexMeta = mock(IndexMetadata.class);
        when(indexMeta.getNumberOfShards()).thenReturn(1);
        when(indexMetadataFactory.fromRepo("snap-1", "idx")).thenReturn(indexMeta);
        when(repoDataProvider.getIndexId("idx")).thenReturn("id-1");

        var shardMeta = mock(ShardMetadata.class);
        when(shardMetadataFactory.fromRepo("snap-1", "idx", 0)).thenReturn(shardMeta);

        var shards = extractor.listShards("snap-1", "idx");
        assertEquals(1, shards.size());
        assertEquals(shardMeta, shards.get(0).metadata());
    }

    @Test
    void getSnapshotReader_returnsUnderlyingReader() {
        var extractor = createExtractor();
        assertEquals(snapshotReader, extractor.getSnapshotReader());
    }

    @Test
    void shardEntry_recordFields() {
        var meta = mock(ShardMetadata.class);
        var entry = new SnapshotExtractor.ShardEntry("snap", "idx", "id", 5, meta);
        assertEquals("snap", entry.snapshotName());
        assertEquals("idx", entry.indexName());
        assertEquals("id", entry.indexId());
        assertEquals(5, entry.shardId());
        assertEquals(meta, entry.metadata());
    }
}
