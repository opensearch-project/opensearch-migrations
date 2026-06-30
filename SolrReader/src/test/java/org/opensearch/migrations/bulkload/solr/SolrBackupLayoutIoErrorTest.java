package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Exercises the {@code catch (IOException)} branches in {@link SolrBackupLayout} by mocking
 * {@link Files} to fail on directory listing / stream access. These I/O-failure paths are
 * otherwise unreachable from real backups.
 */
class SolrBackupLayoutIoErrorTest {

    private static final Path DIR = Path.of("/backup/root");

    @Test
    void classifyBareBackup_returnsNull_whenAllDirectoryListingsFail() throws IOException {
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(any(Path.class))).thenReturn(true);
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("listing failed"));

            // hasCloudMarkersAtRoot, containsSegmentsFile and findStandaloneSnapshotDir each hit their
            // IOException catch and report "not found", so classification yields null.
            assertThat(SolrBackupLayout.classifyBareBackup(DIR, null), nullValue());
        }
    }

    @Test
    void resolveCollectionDataDir_returnsInput_whenListingFails() throws IOException {
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(any(Path.class))).thenReturn(true);
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("listing failed"));

            // containsBackupDataMarkers and the descent both hit their catch and fall back to the input.
            assertThat(SolrBackupLayout.resolveCollectionDataDir(DIR), equalTo(DIR));
        }
    }

    @Test
    void readCollectionName_returnsNull_whenPropertiesListingFails() throws IOException {
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isRegularFile(any(Path.class))).thenReturn(false);
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("listing failed"));

            assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(DIR), nullValue());
        }
    }

    @Test
    void readCollectionName_returnsNull_whenPropertiesStreamFails() throws IOException {
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isRegularFile(any(Path.class))).thenReturn(true);
            files.when(() -> Files.newInputStream(any(Path.class))).thenThrow(new IOException("read failed"));

            assertThat(SolrBackupLayout.readCollectionNameFromBackupProperties(DIR), nullValue());
        }
    }

    @Test
    void findLatestZkBackup_fallsBackToBareDir_whenListingFails() throws IOException {
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(any(Path.class))).thenReturn(true);
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("listing failed"));

            var result = SolrBackupLayout.findLatestZkBackup(DIR);

            assertThat(result, notNullValue());
            assertThat(result.getFileName().toString(), equalTo("zk_backup"));
        }
    }

    @Test
    void countShards_returnsOne_whenShardEnumerationFails() throws IOException {
        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.isDirectory(any(Path.class))).thenReturn(true);
            files.when(() -> Files.list(any(Path.class))).thenThrow(new IOException("listing failed"));

            // findLatestShardMetadataFiles and the shard-dir enumeration both hit their catch,
            // so countShards falls through to the single-shard default.
            assertThat(SolrBackupLayout.countShards(DIR), is(1));
        }
    }
}
