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
}
