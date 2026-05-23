package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

class SolrBackupLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    void findLatestZkBackup_singleBackup() throws IOException {
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("zk_backup_0/configs/myconfig"));
        Files.createDirectories(collectionDir.resolve("index"));

        var latest = SolrBackupLayout.findLatestZkBackup(collectionDir);
        assertThat(latest.getFileName().toString(), equalTo("zk_backup_0"));
    }

    @Test
    void findLatestZkBackup_multipleBackups() throws IOException {
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("zk_backup_0/configs/configA"));
        Files.createDirectories(collectionDir.resolve("zk_backup_1/configs/configA"));
        Files.createDirectories(collectionDir.resolve("zk_backup_2/configs/configA"));
        Files.createDirectories(collectionDir.resolve("index"));
        Files.createDirectories(collectionDir.resolve("shard_backup_metadata"));

        var latest = SolrBackupLayout.findLatestZkBackup(collectionDir);
        assertThat(latest.getFileName().toString(), equalTo("zk_backup_2"));
    }

    @Test
    void findLatestZkBackup_noZkBackupDirs() throws IOException {
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("index"));
        Files.createDirectories(collectionDir.resolve("shard_backup_metadata"));

        var latest = SolrBackupLayout.findLatestZkBackup(collectionDir);
        assertThat(latest, nullValue());
    }

    @Test
    void findLatestZkBackup_nonExistentDir() {
        var latest = SolrBackupLayout.findLatestZkBackup(tempDir.resolve("nonexistent"));
        assertThat(latest, nullValue());
    }

    @Test
    void findLatestZkBackup_bareZkBackupFallback_solr6Or7Layout() throws IOException {
        // Solr 6/7 non-incremental SolrCloud BACKUP writes a bare zk_backup/ directory
        // (no numeric suffix). findLatestZkBackup must fall back to it.
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("zk_backup/configs/myconfig"));
        Files.createFile(collectionDir.resolve("backup.properties"));

        var latest = SolrBackupLayout.findLatestZkBackup(collectionDir);
        assertThat(latest, org.hamcrest.CoreMatchers.notNullValue());
        assertThat(latest.getFileName().toString(), equalTo("zk_backup"));
    }

    @Test
    void containsBackupDataMarkers_solr6SnapshotShardDir() throws IOException {
        // Solr 6 SolrCloud backup contains snapshot.shardN directories — these should
        // be recognised as backup data markers so resolveCollectionDataDir returns the
        // correct collection directory without descending an extra level.
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("snapshot.shard1"));
        Files.createDirectories(collectionDir.resolve("snapshot.shard2"));
        Files.createFile(collectionDir.resolve("backup.properties"));

        // resolveCollectionDataDir should return collectionDir itself (not descend further)
        var resolved = SolrBackupLayout.resolveCollectionDataDir(collectionDir);
        assertThat(resolved, equalTo(collectionDir));
    }

    @Test
    void findLatestZkBackup_numberedRevisionWinsOverBare() throws IOException {
        // If both numbered and unnumbered zk_backups exist (highly unusual), the
        // numbered one takes precedence — newer Solr versions are authoritative.
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("zk_backup/configs/legacy"));
        Files.createDirectories(collectionDir.resolve("zk_backup_0/configs/modern"));

        var latest = SolrBackupLayout.findLatestZkBackup(collectionDir);
        assertThat(latest.getFileName().toString(), equalTo("zk_backup_0"));
    }

    @Test
    void findLatestZkBackup_ignoresNonMatching() throws IOException {
        var collectionDir = tempDir.resolve("myCollection");
        Files.createDirectories(collectionDir.resolve("zk_backup_0/configs/cfg"));
        Files.createDirectories(collectionDir.resolve("zk_backup_3/configs/cfg"));
        Files.createDirectories(collectionDir.resolve("not_zk_backup_99"));
        Files.createDirectories(collectionDir.resolve("zk_backup_nope"));

        var latest = SolrBackupLayout.findLatestZkBackup(collectionDir);
        assertThat(latest.getFileName().toString(), equalTo("zk_backup_3"));
    }

    @Test
    void findLatestZkBackupName_fromList() {
        var names = List.of("index", "shard_backup_metadata", "zk_backup_0", "zk_backup_1", "zk_backup_2");
        var latest = SolrBackupLayout.findLatestZkBackupName(names);
        assertThat(latest, equalTo("zk_backup_2"));
    }

    @Test
    void findLatestZkBackupName_emptyList() {
        var latest = SolrBackupLayout.findLatestZkBackupName(List.of());
        assertThat(latest, nullValue());
    }

    @Test
    void findLatestZkBackupName_noMatches() {
        var latest = SolrBackupLayout.findLatestZkBackupName(List.of("index", "shard_backup_metadata"));
        assertThat(latest, nullValue());
    }

    @Test
    void findLatestShardMetadataFiles_singleBackup() throws IOException {
        var metadataDir = tempDir.resolve("shard_backup_metadata");
        Files.createDirectories(metadataDir);
        Files.writeString(metadataDir.resolve("md_shard1_0.json"), "{\"file1\":{\"fileName\":\"a.dat\"}}");

        var latest = SolrBackupLayout.findLatestShardMetadataFiles(metadataDir);
        assertThat(latest, hasSize(1));
        assertThat(latest.get(0).getFileName().toString(), equalTo("md_shard1_0.json"));
    }

    @Test
    void findLatestShardMetadataFiles_multipleBackups() throws IOException {
        var metadataDir = tempDir.resolve("shard_backup_metadata");
        Files.createDirectories(metadataDir);
        Files.writeString(metadataDir.resolve("md_shard1_0.json"), "{\"old\":{\"fileName\":\"old.dat\"}}");
        Files.writeString(metadataDir.resolve("md_shard1_1.json"), "{\"new\":{\"fileName\":\"new.dat\"}}");

        var latest = SolrBackupLayout.findLatestShardMetadataFiles(metadataDir);
        assertThat(latest, hasSize(1));
        assertThat(latest.get(0).getFileName().toString(), equalTo("md_shard1_1.json"));
    }

    @Test
    void findLatestShardMetadataFiles_multipleShards_multipleBackups() throws IOException {
        var metadataDir = tempDir.resolve("shard_backup_metadata");
        Files.createDirectories(metadataDir);
        // shard1: two revisions
        Files.writeString(metadataDir.resolve("md_shard1_0.json"), "{}");
        Files.writeString(metadataDir.resolve("md_shard1_1.json"), "{}");
        // shard2: two revisions
        Files.writeString(metadataDir.resolve("md_shard2_0.json"), "{}");
        Files.writeString(metadataDir.resolve("md_shard2_1.json"), "{}");

        var latest = SolrBackupLayout.findLatestShardMetadataFiles(metadataDir);
        assertThat(latest, hasSize(2));
        assertThat(latest.get(0).getFileName().toString(), equalTo("md_shard1_1.json"));
        assertThat(latest.get(1).getFileName().toString(), equalTo("md_shard2_1.json"));
    }

    @Test
    void findLatestShardMetadataFiles_emptyDir() throws IOException {
        var metadataDir = tempDir.resolve("shard_backup_metadata");
        Files.createDirectories(metadataDir);

        var latest = SolrBackupLayout.findLatestShardMetadataFiles(metadataDir);
        assertThat(latest, hasSize(0));
    }

    @Test
    void findLatestShardMetadataFiles_nonExistentDir() {
        var latest = SolrBackupLayout.findLatestShardMetadataFiles(tempDir.resolve("nonexistent"));
        assertThat(latest, hasSize(0));
    }

    @Test
    void countShards_nullPath() {
        assertThat(SolrBackupLayout.countShards(null), equalTo(1));
    }

    @Test
    void countShards_nonExistentPath() {
        assertThat(SolrBackupLayout.countShards(tempDir.resolve("nonexistent")), equalTo(1));
    }

    @Test
    void countShards_emptyDirectory() throws IOException {
        var collectionDir = tempDir.resolve("emptyCollection");
        Files.createDirectories(collectionDir);
        assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(1));
    }

    @Test
    void countShards_shardBackupMetadata_fourLatestFiles() throws IOException {
        var collectionDir = tempDir.resolve("incrementalCollection");
        var metadataDir = collectionDir.resolve("shard_backup_metadata");
        Files.createDirectories(metadataDir);
        // Four shards, each with a superseded older revision (_0) and a newer one (_1)
        for (int i = 1; i <= 4; i++) {
            Files.writeString(metadataDir.resolve("md_shard" + i + "_0.json"), "{\"old\":{}}");
            Files.writeString(metadataDir.resolve("md_shard" + i + "_1.json"), "{\"new\":{}}");
        }
        assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(4));
    }

    @Test
    void countShards_emptyShardBackupMetadata_fallsThroughToShardDirs() throws IOException {
        var collectionDir = tempDir.resolve("fallthroughCollection");
        Files.createDirectories(collectionDir.resolve("shard_backup_metadata"));
        // Provide shard subdirectories so the fallthrough strategy yields a known value
        Files.createDirectories(collectionDir.resolve("shard1"));
        Files.createFile(collectionDir.resolve("shard1").resolve("segments_1"));
        Files.createDirectories(collectionDir.resolve("shard2"));
        Files.createFile(collectionDir.resolve("shard2").resolve("segments_1"));

        assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(2));
    }

    @Test
    void countShards_directShardSubdirsWithSegments() throws IOException {
        var collectionDir = tempDir.resolve("directShardsCollection");
        Files.createDirectories(collectionDir.resolve("shard1"));
        Files.createFile(collectionDir.resolve("shard1").resolve("segments_1"));
        Files.createDirectories(collectionDir.resolve("shard2"));
        Files.createFile(collectionDir.resolve("shard2").resolve("segments_1"));

        assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(2));
    }

    @Test
    void countShards_nestedDataIndexLayout() throws IOException {
        var collectionDir = tempDir.resolve("nestedCollection");
        var index = collectionDir.resolve("shard1").resolve("data").resolve("index");
        Files.createDirectories(index);
        Files.createFile(index.resolve("segments_1"));

        assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(1));
    }

    @Test
    void countShards_mixedShardAndNonShardDirs() throws IOException {
        var collectionDir = tempDir.resolve("mixedCollection");
        Files.createDirectories(collectionDir.resolve("shard1"));
        Files.createFile(collectionDir.resolve("shard1").resolve("segments_1"));
        // "stats/" looks like a directory but lacks a segments_ file -> must NOT be counted
        Files.createDirectories(collectionDir.resolve("stats"));
        Files.writeString(collectionDir.resolve("stats").resolve("info.txt"), "not a shard");

        assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(1));
    }

    @Test
    void countShards_unreadableDirectory_fallsBackToOne() throws IOException {
        var collectionDir = tempDir.resolve("unreadableCollection");
        Files.createDirectories(collectionDir);
        var perms = collectionDir.toFile().setReadable(false, false);
        if (!perms) {
            // Skip if the platform refuses to remove read permission (e.g. running as root)
            return;
        }
        try {
            assertThat(SolrBackupLayout.countShards(collectionDir), equalTo(1));
        } finally {
            collectionDir.toFile().setReadable(true, false);
        }
    }
}
