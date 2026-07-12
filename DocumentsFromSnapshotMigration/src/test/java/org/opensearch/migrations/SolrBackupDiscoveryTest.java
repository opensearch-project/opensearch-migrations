package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.solr.SolrBackupReadException;
import org.opensearch.migrations.bulkload.solr.SolrShardPartition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In-process coverage for {@link SolrBackupDiscovery}, exercising the same S3/filesystem branches
 * the forked document-migration E2E tests hit, but without forking.
 */
class SolrBackupDiscoveryTest {

    @TempDir
    Path backupDir;

    private S3Repo s3Repo() {
        var s3Repo = mock(S3Repo.class);
        lenient().when(s3Repo.getRepoRootDir()).thenReturn(backupDir);
        return s3Repo;
    }

    // ---- detection + collection resolution ----

    @Test
    void s3CloudBare_recoversCollectionNameFromBackupProperties() throws Exception {
        Files.writeString(backupDir.resolve("backup.properties"), "collection=nyc_taxis\n");
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("zk_backup", "snapshot.shard1"));

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        assertThat(discovery.collections(), contains("nyc_taxis"));
        assertThat(discovery.dataDirByCollection().get("nyc_taxis"), equalTo(""));
        assertThat(discovery.shardPreparationNeeded(), is(true));
        verify(s3Repo).downloadFile("backup.properties");
    }

    @Test
    void s3CloudBare_propertiesReadFailure_leavesNameNull_fallsBackToTopLevelListing() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("zk_backup"));
        when(s3Repo.downloadFile("backup.properties")).thenThrow(new RuntimeException("boom"));

        // CLOUD with null name is not a single-collection bare backup; falls back to the listing.
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        assertThat(discovery.dataDirByCollection().isEmpty(), is(true));
        assertThat(discovery.collections(), contains("zk_backup"));
    }

    @Test
    void s3StandaloneBare_derivesNameFromSnapshotDir() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("snapshot.products"));

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        assertThat(discovery.collections(), contains("products"));
        assertThat(discovery.dataDirByCollection().get("products"), equalTo("snapshot.products"));
        assertThat(discovery.shardPreparationNeeded(), is(false));
    }

    @Test
    void s3StandaloneFlatRoot_derivesNameFromKeyLastSegment() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("segments_2", "_0.si", "_0.fdt"));
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://bucket/backups/standalone/snapshot.nyc_taxis_7"));

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        assertThat(discovery.collections(), contains("nyc_taxis_7"));
        assertThat(discovery.dataDirByCollection().get("nyc_taxis_7"), equalTo(""));
        assertThat(discovery.shardPreparationNeeded(), is(false));
    }

    @Test
    void s3StandaloneFlatRoot_prepareCollection_downloadsWholeRoot() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("segments_2"));
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://bucket/snapshot.catalog"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareCollection("catalog");

        verify(s3Repo).downloadPrefix("");
    }

    @Test
    void s3StandaloneFlatRoot_atBucketRoot_emptyKeyRejected() {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("segments_2"));
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://bucket"));

        // A flat standalone index at the bucket root has no path segment to name the index after;
        // it is rejected rather than migrated into a blank index name.
        assertThrows(SolrBackupReadException.class,
            () -> SolrBackupDiscovery.discover(s3Repo, backupDir, List.of()));
    }

    @Test
    void s3NoSubdirsNoSegments_notBare_fallsBackToEmptyListing() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("random.txt"));
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://bucket/whatever"));

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        assertThat(discovery.collections().isEmpty(), is(true));
    }

    @Test
    void s3FlatRootProbe_listFilesFailure_notBare() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenThrow(new RuntimeException("cannot list root"));

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        assertThat(discovery.collections().isEmpty(), is(true));
    }

    @Test
    void s3Wrapped_notBare_usesTopLevelDirectories_filteredByAllowlist() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a", "col_b", "col_c"));

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of("col_a", "col_c"));

        assertThat(discovery.collections(), containsInAnyOrder("col_a", "col_c"));
        assertThat(discovery.dataDirByCollection().isEmpty(), is(true));
    }

    @Test
    void filesystemStandaloneBare_classifiesFromDisk() throws Exception {
        var snapshotDir = backupDir.resolve("snapshot.catalog");
        Files.createDirectories(snapshotDir);
        Files.createFile(snapshotDir.resolve("segments_1"));

        var discovery = SolrBackupDiscovery.discover(null, backupDir, List.of());

        assertThat(discovery.collections(), contains("catalog"));
        assertThat(discovery.dataDirByCollection().get("catalog"), equalTo("snapshot.catalog"));
        assertThat(discovery.shardPreparationNeeded(), is(false));
    }

    @Test
    void detectBareLayout_nullForWrappedS3Listing() {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a", "col_b"));

        assertThat(SolrBackupDiscovery.detectBareLayout(s3Repo, backupDir), nullValue());
    }

    // ---- collection preparation (lazy S3 downloads) ----

    @Test
    void prepareCollection_standalone_downloadsWholeDataDir() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("snapshot.products"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareCollection("products");

        verify(s3Repo).downloadPrefix("snapshot.products");
    }

    @Test
    void prepareCollection_cloudKnownDataDir_downloadsZkAndShardMeta_andCreatesSnapshotStubDirs() throws Exception {
        Files.writeString(backupDir.resolve("backup.properties"), "collection=events\n");
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("zk_backup", "snapshot.shard1"));
        when(s3Repo.listSubDirectories("")).thenReturn(List.of("zk_backup", "snapshot.shard1"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareCollection("events");

        verify(s3Repo).downloadPrefix("zk_backup");
        verify(s3Repo).downloadPrefix("shard_backup_metadata");
        assertThat(Files.isDirectory(backupDir.resolve("snapshot.shard1")), is(true));
    }

    @Test
    void prepareCollection_cloudUnknownCollection_resolvedPrefix_downloads() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a"));
        when(s3Repo.listSubDirectories("col_a")).thenReturn(List.of("zk_backup_0"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareCollection("col_a");

        verify(s3Repo).downloadPrefix("col_a/zk_backup_0");
        verify(s3Repo).downloadPrefix("col_a/shard_backup_metadata");
        assertThat(discovery.dataDirByCollection().get("col_a"), equalTo("col_a"));
    }

    @Test
    void prepareCollection_cloudUnknownCollection_unresolved_warnsAndSkipsDownloads() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a"));
        when(s3Repo.listSubDirectories("col_a")).thenReturn(List.of());
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareCollection("col_a");

        verify(s3Repo, never()).downloadPrefix("col_a/shard_backup_metadata");
    }

    @Test
    void prepareCollection_filesystem_noDownloads_justParsesSchema() throws Exception {
        var snapshotDir = backupDir.resolve("snapshot.catalog");
        Files.createDirectories(snapshotDir);
        Files.createFile(snapshotDir.resolve("segments_1"));
        var discovery = SolrBackupDiscovery.discover(null, backupDir, List.of());

        // No S3 repo, so this only resolves+parses the (empty) schema without throwing.
        discovery.prepareCollection("catalog");

        assertThat(discovery.schemas().containsKey("catalog"), is(true));
    }

    // ---- shard preparation (lazy per-shard S3 downloads) ----

    @Test
    void prepareShard_withFileNameMapping_downloadsEachMappedFile() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareShard(new SolrShardPartition("col_a", "shard1", null, Map.of("_0.cfs", "uuid-1")));

        verify(s3Repo).downloadFile("col_a/index/uuid-1");
    }

    @Test
    void prepareShard_noMapping_snapshotShard_downloadsSnapshotPrefix() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareShard(new SolrShardPartition("col_a", "snapshot.shard1"));

        verify(s3Repo).downloadPrefix("col_a/snapshot.shard1");
    }

    @Test
    void prepareShard_noMapping_plainShard_downloadsIndexPrefix() throws Exception {
        var s3Repo = s3Repo();
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a"));
        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, List.of());

        discovery.prepareShard(new SolrShardPartition("col_a", "shard1"));

        verify(s3Repo).downloadPrefix("col_a/index");
    }

    @Test
    void shardPreparationNeeded_falseForFilesystem() throws Exception {
        var discovery = SolrBackupDiscovery.discover(null, backupDir, List.of());
        assertThat(discovery.shardPreparationNeeded(), is(false));
    }
}
