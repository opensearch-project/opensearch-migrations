package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout.SolrBackupMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Direct unit tests for the pure {@link SolrBackupLayout} helpers (listing/prefix/properties logic)
 * that are otherwise only exercised through S3 or forked paths.
 */
class SolrBackupLayoutHelpersTest {

    @TempDir
    Path tempDir;

    // ---- findLatestZkBackupName ----

    @Test
    void findLatestZkBackupName_picksHighestNumberedBackup() {
        assertThat(
            SolrBackupLayout.findLatestZkBackupName(List.of("zk_backup_0", "zk_backup_2", "zk_backup_1")),
            equalTo("zk_backup_2"));
    }

    @Test
    void findLatestZkBackupName_fallsBackToBareZkBackup() {
        assertThat(SolrBackupLayout.findLatestZkBackupName(List.of("index", "zk_backup")), equalTo("zk_backup"));
    }

    @Test
    void findLatestZkBackupName_prefersNumberedOverBare() {
        assertThat(
            SolrBackupLayout.findLatestZkBackupName(List.of("zk_backup", "zk_backup_3")),
            equalTo("zk_backup_3"));
    }

    @Test
    void findLatestZkBackupName_nullWhenNonePresent() {
        assertThat(SolrBackupLayout.findLatestZkBackupName(List.of("index", "data")), nullValue());
    }

    // ---- resolveCollectionDataPrefix ----

    @Test
    void resolveCollectionDataPrefix_flatLayout() {
        Function<String, List<String>> listing = Map.of(
            "col", List.of("zk_backup_0", "shard_backup_metadata", "index"))::get;

        var resolved = SolrBackupLayout.resolveCollectionDataPrefix("col", listing);

        assertThat(resolved.dataPrefix(), equalTo(""));
        assertThat(resolved.latestZkBackupName(), equalTo("zk_backup_0"));
        assertThat(resolved.joinWith("col"), equalTo("col"));
    }

    @Test
    void resolveCollectionDataPrefix_twoLevelLayout() {
        Function<String, List<String>> listing = key -> switch (key) {
            case "col" -> List.of("snapshot");
            case "col/snapshot" -> List.of("zk_backup_1", "index");
            default -> List.of();
        };

        var resolved = SolrBackupLayout.resolveCollectionDataPrefix("col", listing);

        assertThat(resolved.dataPrefix(), equalTo("snapshot"));
        assertThat(resolved.latestZkBackupName(), equalTo("zk_backup_1"));
        assertThat(resolved.joinWith("col"), equalTo("col/snapshot"));
    }

    @Test
    void resolveCollectionDataPrefix_nullWhenNoZkBackupAnywhere() {
        Function<String, List<String>> listing = key -> List.of("index");
        assertThat(SolrBackupLayout.resolveCollectionDataPrefix("col", listing), nullValue());
    }

    // ---- buildBackupS3Uri ----

    @Test
    void buildBackupS3Uri_noSubpath() {
        assertThat(
            SolrBackupLayout.buildBackupS3Uri(new S3Uri("s3://my-bucket"), "snap1"),
            equalTo("s3://my-bucket/snap1"));
    }

    @Test
    void buildBackupS3Uri_withSubpath() {
        assertThat(
            SolrBackupLayout.buildBackupS3Uri(new S3Uri("s3://my-bucket/dir1/dir2"), "snap1"),
            equalTo("s3://my-bucket/dir1/dir2/snap1"));
    }

    // ---- joinPrefix ----

    @Test
    void joinPrefix_emptyBaseReturnsSuffix() {
        assertThat(SolrBackupLayout.joinPrefix("", "index"), equalTo("index"));
    }

    @Test
    void joinPrefix_nonEmptyBaseJoinsWithSlash() {
        assertThat(SolrBackupLayout.joinPrefix("col/data", "index"), equalTo("col/data/index"));
    }

    // ---- detectBareLayoutFromListing ----

    @Test
    void detectBareLayoutFromListing_cloudFromZkBackup() {
        var layout = SolrBackupLayout.detectBareLayoutFromListing(List.of("zk_backup", "snapshot.shard1"));
        assertThat(layout.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(layout.collectionName(), nullValue());
        assertThat(layout.dataPath(), equalTo(""));
    }

    @Test
    void detectBareLayoutFromListing_cloudFromNumberedZkBackup() {
        assertThat(
            SolrBackupLayout.detectBareLayoutFromListing(List.of("zk_backup_0")).mode(),
            equalTo(SolrBackupMode.CLOUD));
    }

    @Test
    void detectBareLayoutFromListing_cloudFromShardBackupMetadata() {
        assertThat(
            SolrBackupLayout.detectBareLayoutFromListing(List.of("shard_backup_metadata", "index")).mode(),
            equalTo(SolrBackupMode.CLOUD));
    }

    @Test
    void detectBareLayoutFromListing_standaloneFromSnapshotDir() {
        var layout = SolrBackupLayout.detectBareLayoutFromListing(List.of("snapshot.mycore"));
        assertThat(layout.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(layout.collectionName(), equalTo("mycore"));
        assertThat(layout.dataPath(), equalTo("snapshot.mycore"));
    }

    @Test
    void detectBareLayoutFromListing_nullForWrappedLayout() {
        assertThat(SolrBackupLayout.detectBareLayoutFromListing(List.of("col_a", "col_b")), nullValue());
    }

    // ---- readCollectionNameFromBackupProperties ----

    @Test
    void readCollectionName_fromCollectionKey() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collection=nyc_taxis\nindex.version=7.7.3\n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), equalTo("nyc_taxis"));
    }

    @Test
    void readCollectionName_fallsBackToCollectionNameKey() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collectionName=products\n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), equalTo("products"));
    }

    @Test
    void readCollectionName_picksLatestNumberedPropertiesFile() throws IOException {
        Files.writeString(tempDir.resolve("backup_0.properties"), "collection=old\n");
        Files.writeString(tempDir.resolve("backup_1.properties"), "collection=new\n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), equalTo("new"));
    }

    @Test
    void readCollectionName_nullWhenNoPropertiesFile() {
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), nullValue());
    }

    @Test
    void readCollectionName_nullWhenKeyAbsent() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "index.version=7.7.3\n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), nullValue());
    }

    // ---- countShards ----

    @Test
    void countShards_fromShardBackupMetadata_latestPerShard() throws IOException {
        var meta = Files.createDirectories(tempDir.resolve("shard_backup_metadata"));
        Files.createFile(meta.resolve("md_shard1_0.json"));
        Files.createFile(meta.resolve("md_shard1_1.json"));
        Files.createFile(meta.resolve("md_shard2_0.json"));

        assertThat(SolrBackupLayout.countShards(tempDir), equalTo(2));
    }

    @Test
    void countShards_fromShardDirectoriesWithSegments() throws IOException {
        for (var shard : List.of("snapshot.shard1", "snapshot.shard2", "snapshot.shard3")) {
            var dir = Files.createDirectories(tempDir.resolve(shard));
            Files.createFile(dir.resolve("segments_1"));
        }
        assertThat(SolrBackupLayout.countShards(tempDir), equalTo(3));
    }

    @Test
    void countShards_singleWhenNoShardMarkers() throws IOException {
        Files.createDirectories(tempDir.resolve("index"));
        assertThat(SolrBackupLayout.countShards(tempDir), equalTo(1));
    }

    @Test
    void countShards_singleForNonExistentDir() {
        assertThat(SolrBackupLayout.countShards(tempDir.resolve("does_not_exist")), is(1));
    }
}
