package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSpec;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.bulkload.solr.SolrMultiCollectionSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * In-process coverage for {@link SolrBackupSourceProvider}, which otherwise only runs inside the
 * forked migration subprocess. Exercises the filesystem path (no S3 required).
 */
class RfsSolrDocumentSourceTest {

    @TempDir
    Path backupDir;

    @TempDir
    Path scratchDir;

    private final SolrBackupSourceProvider provider = new SolrBackupSourceProvider();

    private SourceRuntime runtime() {
        return new SourceRuntime(scratchDir, scratchDir, () -> null);
    }

    private SolrBackupSpec solr7Spec() {
        return new SolrBackupSpec("file://" + backupDir, null, 7, List.of(), null, null);
    }

    @Test
    void open_bareCloudFilesystemBackup() throws Exception {
        Files.writeString(backupDir.resolve("backup.properties"), "collection=nyc_taxis\n");
        var shard = Files.createDirectories(backupDir.resolve("snapshot.shard1"));
        Files.createFile(shard.resolve("segments_1"));
        Files.createDirectories(backupDir.resolve("zk_backup/configs/nyc_taxis_configs"));

        var source = provider.open(solr7Spec().toJson(), runtime());

        assertThat(source, notNullValue());
        assertThat(source, instanceOf(SolrMultiCollectionSource.class));
        assertThat(source.listCollections(), contains("nyc_taxis"));
    }

    @Test
    void open_standaloneFilesystemBackup() throws Exception {
        var snapshotDir = Files.createDirectories(backupDir.resolve("snapshot.catalog"));
        Files.createFile(snapshotDir.resolve("segments_1"));

        var source = provider.open(solr7Spec().toJson(), runtime());

        assertThat(source, notNullValue());
        assertThat(source.listCollections(), contains("catalog"));
    }

    @Test
    void validate_rejectsUnsupportedSolrMajorVersion() {
        var spec = new SolrBackupSpec("file://" + backupDir, null, 5, List.of(), null, null);

        assertThrows(IllegalArgumentException.class, () -> provider.validate(spec, runtime()));
    }

    @Test
    void validate_rejectsNonFileOrS3Uri() {
        var spec = new SolrBackupSpec("gs://bucket/backup", null, 8, List.of(), null, null);

        assertThrows(IllegalArgumentException.class, () -> provider.validate(spec, runtime()));
    }

    /** Construction lists and downloads, so the caller is told to check for work first. */
    @Test
    void deferUntilWorkAvailable() {
        assertThat(provider.deferUntilWorkAvailable(), is(true));
    }
}
