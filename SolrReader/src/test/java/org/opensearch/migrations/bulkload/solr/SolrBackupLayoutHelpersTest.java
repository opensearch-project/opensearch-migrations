package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout.SolrBackupMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // ---- detectFlatRootStandaloneFromS3 ----

    @Test
    void detectFlatRootStandaloneFromS3_derivesNameFromKeyLastSegment() {
        var layout = SolrBackupLayout.detectFlatRootStandaloneFromS3(
            List.of("segments_2", "_0.si"), "backups/standalone/snapshot.nyc_taxis_7");
        assertThat(layout.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(layout.collectionName(), equalTo("nyc_taxis_7"));
        assertThat(layout.dataPath(), equalTo(""));
    }

    @Test
    void detectFlatRootStandaloneFromS3_stripsPrefixlessKeySegment() {
        var layout = SolrBackupLayout.detectFlatRootStandaloneFromS3(
            List.of("segments_2"), "my_core/");
        assertThat(layout.collectionName(), equalTo("my_core"));
    }

    @Test
    void detectFlatRootStandaloneFromS3_emptyKeyRejected() {
        // A flat index at the bucket root has no path segment to name the index after; rejected.
        assertThrows(SolrBackupReadException.class,
            () -> SolrBackupLayout.detectFlatRootStandaloneFromS3(List.of("segments_2"), ""));
    }

    @Test
    void detectFlatRootStandaloneFromS3_nullWhenNoSegmentsFile() {
        assertThat(
            SolrBackupLayout.detectFlatRootStandaloneFromS3(List.of("_0.si", "random.txt"), "snapshot.x"),
            nullValue());
    }

    // ---- detectBareLayout (lambda-driven composition) ----

    private static Supplier<List<String>> noSubDirs() {
        return List::of;
    }

    private static <T> Supplier<T> mustNotBeCalled(String why) {
        return () -> {
            throw new AssertionError(why);
        };
    }

    @Test
    void detectBareLayout_cloudRecoversNameViaDownloadLambda() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collection=events\n");

        var layout = SolrBackupLayout.detectBareLayout(
            () -> List.of("zk_backup"),
            mustNotBeCalled("root files must not be listed for a cloud sub-dir layout"),
            () -> tempDir,
            mustNotBeCalled("root key must not be read for a cloud sub-dir layout"));

        assertThat(layout.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(layout.collectionName(), equalTo("events"));
    }

    @Test
    void detectBareLayout_cloudNameNullWhenDownloadThrows() {
        Supplier<Path> failingDownload = () -> {
            throw new RuntimeException("download failed");
        };

        var layout = SolrBackupLayout.detectBareLayout(
            () -> List.of("zk_backup"),
            mustNotBeCalled("no root files for a cloud layout"),
            failingDownload,
            mustNotBeCalled("no root key for a cloud layout"));

        assertThat(layout.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(layout.collectionName(), nullValue());
    }

    @Test
    void detectBareLayout_flatRootStandaloneUsesRootFilesAndKey() {
        var layout = SolrBackupLayout.detectBareLayout(
            noSubDirs(),
            () -> List.of("segments_2"),
            mustNotBeCalled("backup.properties must not be downloaded for a standalone index"),
            () -> "backups/snapshot.catalog");

        assertThat(layout.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(layout.collectionName(), equalTo("catalog"));
        assertThat(layout.dataPath(), equalTo(""));
    }

    @Test
    void detectBareLayout_nullForWrappedWithoutProbingRootFilesOrKey() {
        var layout = SolrBackupLayout.detectBareLayout(
            () -> List.of("col_a", "col_b"),
            mustNotBeCalled("root files must not be listed for a wrapped layout"),
            mustNotBeCalled("no download for a wrapped layout"),
            mustNotBeCalled("no root key for a wrapped layout"));

        assertThat(layout, nullValue());
    }

    @Test
    void detectBareLayout_nullWhenRootListingThrows() {
        var layout = SolrBackupLayout.detectBareLayout(
            noSubDirs(),
            () -> {
                throw new RuntimeException("cannot list root");
            },
            mustNotBeCalled("no download when root listing fails"),
            () -> "backups/x");

        assertThat(layout, nullValue());
    }

    // ---- readCollectionNameFromBackupProperties ----

    @Test
    void readCollectionName_fromCollectionKey() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collection=nyc_taxis\nindex.version=7.7.3\n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), equalTo("nyc_taxis"));
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

    // ---- classifyBareBackup: branches the cloud/standalone fixtures don't reach ----

    @Test
    void classifyBareBackup_standaloneFlatRoot_segmentsDirectlyAtRoot() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));

        var layout = SolrBackupLayout.classifyBareBackup(tempDir);

        assertThat(layout.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(layout.dataPath(), equalTo(""));
    }

    @Test
    void classifyBareBackup_cloudFromNumberedZkBackupMarker() throws IOException {
        Files.createDirectories(tempDir.resolve("zk_backup_0"));

        assertThat(SolrBackupLayout.classifyBareBackup(tempDir).mode(), equalTo(SolrBackupMode.CLOUD));
    }

    @Test
    void classifyBareBackup_cloudFromShardBackupMetadataMarker() throws IOException {
        Files.createDirectories(tempDir.resolve("shard_backup_metadata"));

        assertThat(SolrBackupLayout.classifyBareBackup(tempDir).mode(), equalTo(SolrBackupMode.CLOUD));
    }

    @Test
    void classifyBareBackup_cloudFromNumberedBackupPropertiesMarker() throws IOException {
        Files.writeString(tempDir.resolve("backup_0.properties"), "collection=events\n");

        var layout = SolrBackupLayout.classifyBareBackup(tempDir);

        assertThat(layout.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(layout.collectionName(), equalTo("events"));
    }

    @Test
    void classifyBareBackup_nullForNonExistentRoot() {
        assertThat(SolrBackupLayout.classifyBareBackup(tempDir.resolve("missing")), nullValue());
    }

    // ---- resolveCollectionDataDir ----

    @Test
    void resolveCollectionDataDir_returnsDirWhenMarkersAtTopLevel() throws IOException {
        Files.createDirectories(tempDir.resolve("index"));
        assertThat(SolrBackupLayout.resolveCollectionDataDir(tempDir), equalTo(tempDir));
    }

    @Test
    void resolveCollectionDataDir_descendsOneLevelForTwoLevelLayout() throws IOException {
        var inner = Files.createDirectories(tempDir.resolve("inner"));
        Files.createDirectories(inner.resolve("zk_backup_0"));

        assertThat(SolrBackupLayout.resolveCollectionDataDir(tempDir), equalTo(inner));
    }

    @Test
    void resolveCollectionDataDir_returnsInputForNonExistentDir() {
        var missing = tempDir.resolve("missing");
        assertThat(SolrBackupLayout.resolveCollectionDataDir(missing), equalTo(missing));
    }

    // ---- readCollectionNameFromBackupProperties: blank-value + non-.properties branches ----

    @Test
    void readCollectionName_nullWhenCollectionBlank() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collection=   \n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), nullValue());
    }

    @Test
    void readCollectionName_ignoresBackupPrefixedNonPropertiesFile() throws IOException {
        Files.writeString(tempDir.resolve("backup_notes.txt"), "ignore me\n");
        Files.writeString(tempDir.resolve("backup_0.properties"), "collection=picked\n");
        assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(tempDir), equalTo("picked"));
    }

    // ---- countShards ----

    @Test
    void countShards_fromNestedDataIndexSegments() throws IOException {
        var shard = Files.createDirectories(tempDir.resolve("shard1/data/index"));
        Files.createFile(shard.resolve("segments_1"));

        assertThat(SolrBackupLayout.countShards(tempDir), equalTo(1));
    }

    // ---- countShards (original) ----

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
