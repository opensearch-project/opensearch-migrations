package org.opensearch.migrations.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout.SolrBackupMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In-process coverage for {@link ClusterReaderExtractor}'s Solr backup path: the filesystem branch
 * (driven through the public API) and the S3 helper methods (driven with a mocked {@link S3Repo}).
 */
class SolrClusterReaderExtractorTest {

    @TempDir
    Path tempDir;

    private static final Version SOLR_7 = Version.fromString("SOLR 7.7.3");

    private MigrateOrEvaluateArgs fsArgs(Path repoPath) {
        var args = new MigrateOrEvaluateArgs();
        args.sourceVersion = SOLR_7;
        args.repoUri = repoPath.toString();
        return args;
    }

    // ---- filesystem branch via extractClusterReader() ----

    @Test
    void filesystemBareCloud_buildsReader() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collection=nyc_taxis\n");
        var shard = Files.createDirectories(tempDir.resolve("snapshot.shard1"));
        Files.createFile(shard.resolve("segments_1"));
        Files.createDirectories(tempDir.resolve("zk_backup/configs/nyc_taxis_configs"));

        var reader = new ClusterReaderExtractor(fsArgs(tempDir)).extractClusterReader();

        assertThat(reader, notNullValue());
    }

    @Test
    void filesystemStandalone_buildsReader() throws IOException {
        var snapshotDir = Files.createDirectories(tempDir.resolve("snapshot.catalog"));
        Files.createFile(snapshotDir.resolve("segments_1"));

        var reader = new ClusterReaderExtractor(fsArgs(tempDir)).extractClusterReader();

        assertThat(reader, notNullValue());
    }

    @Test
    void filesystemWrapped_discoversCollections() throws IOException {
        Files.createDirectories(tempDir.resolve("col1/zk_backup_0/configs/cfg"));
        Files.createDirectories(tempDir.resolve("col1/index"));

        var reader = new ClusterReaderExtractor(fsArgs(tempDir)).extractClusterReader();

        assertThat(reader, notNullValue());
    }

    // ---- detectBareSolrLayoutInS3 (mocked S3Repo) ----

    @Test
    void detectBareSolrLayoutInS3_cloudRecoversNameFromBackupProperties() throws IOException {
        Files.writeString(tempDir.resolve("backup.properties"), "collection=events\n");
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("zk_backup"));
        when(s3Repo.getRepoRootDir()).thenReturn(tempDir);

        var bare = ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null);

        assertThat(bare.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(bare.collectionName(), equalTo("events"));
        verify(s3Repo).downloadFile("backup.properties");
    }

    @Test
    void detectBareSolrLayoutInS3_cloudOverrideWins() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("zk_backup"));

        var bare = ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, "override_idx");

        assertThat(bare.collectionName(), equalTo("override_idx"));
        verify(s3Repo, never()).downloadFile(anyString());
    }

    @Test
    void detectBareSolrLayoutInS3_flatRootStandaloneDerivesNameFromKey() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("segments_2", "_0.si"));
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://bucket/backups/standalone/snapshot.nyc_taxis_7"));

        var bare = ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null);

        assertThat(bare.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(bare.collectionName(), equalTo("nyc_taxis_7"));
        assertThat(bare.dataPath(), equalTo(""));
    }

    @Test
    void detectBareSolrLayoutInS3_flatRootStandaloneOverrideWins() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("segments_2"));

        var bare = ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, "override_idx");

        assertThat(bare.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(bare.collectionName(), equalTo("override_idx"));
    }

    @Test
    void detectBareSolrLayoutInS3_nullWhenNoSubdirsAndNoRootSegments() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenReturn(List.of("notes.txt"));
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://bucket/empty"));

        assertThat(ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null), nullValue());
    }

    @Test
    void detectBareSolrLayoutInS3_nullWhenRootListingFails() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of());
        when(s3Repo.listFilesInS3Root()).thenThrow(new RuntimeException("cannot list root"));

        assertThat(ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null), nullValue());
    }

    @Test
    void detectBareSolrLayoutInS3_standalone() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("snapshot.products"));

        var bare = ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null);

        assertThat(bare.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(bare.collectionName(), equalTo("products"));
        assertThat(bare.dataPath(), equalTo("snapshot.products"));
    }

    @Test
    void detectBareSolrLayoutInS3_nullForWrapped() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("col_a", "col_b"));

        assertThat(ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null), nullValue());
    }

    @Test
    void detectBareSolrLayoutInS3_propertiesFailureLeavesNameNull() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listTopLevelDirectories()).thenReturn(List.of("zk_backup"));
        when(s3Repo.downloadFile("backup.properties")).thenThrow(new RuntimeException("boom"));
        when(s3Repo.getRepoRootDir()).thenReturn(tempDir);

        var bare = ClusterReaderExtractor.detectBareSolrLayoutInS3(s3Repo, null);

        assertThat(bare.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(bare.collectionName(), nullValue());
    }

    // ---- downloadZkBackupForDataDir / downloadZkBackupForCollection (mocked S3Repo) ----

    private ClusterReaderExtractor extractor() {
        return new ClusterReaderExtractor(new MigrateOrEvaluateArgs());
    }

    @Test
    void downloadZkBackupForDataDir_downloadsLatest() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listSubDirectories("")).thenReturn(List.of("zk_backup", "snapshot.shard1"));

        extractor().downloadZkBackupForDataDir(s3Repo, "");

        verify(s3Repo).downloadPrefix("zk_backup");
    }

    @Test
    void downloadZkBackupForDataDir_warnsAndSkipsWhenNone() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listSubDirectories("data")).thenReturn(List.of("index"));

        extractor().downloadZkBackupForDataDir(s3Repo, "data");

        verify(s3Repo, never()).downloadPrefix(anyString());
    }

    @Test
    void downloadZkBackupForCollection_downloadsResolvedPrefix() {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.listSubDirectories("col_a")).thenReturn(List.of("zk_backup_0", "index"));

        extractor().downloadZkBackupForCollection(s3Repo, "col_a");

        verify(s3Repo).downloadPrefix("col_a/zk_backup_0");
    }

    @Test
    void downloadZkBackupForCollection_warnsAndSkipsWhenUnresolved() {
        var s3Repo = mock(S3Repo.class);
        lenient().when(s3Repo.listSubDirectories(anyString())).thenReturn(List.of("index"));

        extractor().downloadZkBackupForCollection(s3Repo, "col_a");

        verify(s3Repo, never()).downloadPrefix(anyString());
    }
}
