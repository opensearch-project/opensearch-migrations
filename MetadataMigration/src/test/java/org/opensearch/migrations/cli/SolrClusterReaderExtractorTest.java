package org.opensearch.migrations.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.S3Repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
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
