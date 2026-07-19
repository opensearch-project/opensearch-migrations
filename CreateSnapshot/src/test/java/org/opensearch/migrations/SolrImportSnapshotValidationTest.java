package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fast, container-free unit tests for IMPORT-mode snapshot-location validation. The end-to-end
 * behavior is covered by TestCreateSnapshotModeFlag (isolatedTest); these exercise the pure
 * empty/missing decision that must fail rather than warn.
 */
public class SolrImportSnapshotValidationTest {

    @Test
    void s3_emptyPrefix_throws() {
        var ex = assertThrows(SolrBackupStrategy.SolrImportSnapshotUnavailable.class,
            () -> SolrBackupStrategy.requireNonEmptyS3Snapshot("bucket", "snap/", true));
        assertThat(ex.getMessage(), containsString("s3://bucket/snap/"));
    }

    @Test
    void s3_nonEmptyPrefix_passes() {
        assertDoesNotThrow(() -> SolrBackupStrategy.requireNonEmptyS3Snapshot("bucket", "snap/", false));
    }

    @Test
    void filesystem_missingDirectory_throws(@TempDir Path tempRepo) {
        var missing = tempRepo.resolve("does-not-exist");
        var ex = assertThrows(SolrBackupStrategy.SolrImportSnapshotUnavailable.class,
            () -> SolrBackupStrategy.requireFilesystemSnapshotPresent(missing));
        assertThat(ex.getMessage(), containsString(missing.toString()));
    }

    @Test
    void filesystem_fileInsteadOfDirectory_throws(@TempDir Path tempRepo) throws Exception {
        var file = tempRepo.resolve("snapshot-as-file");
        Files.writeString(file, "not a directory");
        assertThrows(SolrBackupStrategy.SolrImportSnapshotUnavailable.class,
            () -> SolrBackupStrategy.requireFilesystemSnapshotPresent(file));
    }

    @Test
    void filesystem_existingDirectory_passes(@TempDir Path tempRepo) throws Exception {
        var dir = tempRepo.resolve("snapshot");
        Files.createDirectories(dir);
        assertDoesNotThrow(() -> SolrBackupStrategy.requireFilesystemSnapshotPresent(dir));
    }
}
