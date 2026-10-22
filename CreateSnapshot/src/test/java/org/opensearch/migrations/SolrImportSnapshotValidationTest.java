package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void s3_listingEmpty_returnsTrue() {
        var s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(ListObjectsV2Response.builder().build());
        assertTrue(SolrBackupStrategy.isS3SnapshotEmpty(s3, "bucket", "snap/"));
    }

    @Test
    void s3_listingNonEmpty_returnsFalse() {
        var s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("snap/segments_1").build()).build());
        assertFalse(SolrBackupStrategy.isS3SnapshotEmpty(s3, "bucket", "snap/"));
    }

    @Test
    void s3_listingFailure_wrapsInSnapshotUnavailable() {
        var s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenThrow(new RuntimeException("bucket does not exist"));
        var ex = assertThrows(SolrBackupStrategy.SolrImportSnapshotUnavailable.class,
            () -> SolrBackupStrategy.isS3SnapshotEmpty(s3, "bucket", "snap/"));
        assertThat(ex.getMessage(), containsString("could not verify"));
        assertThat(ex.getMessage(), containsString("bucket does not exist"));
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
