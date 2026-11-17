package org.opensearch.migrations.bulkload.common;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.opensearch.migrations.bulkload.common.GcsRepo.CannotFindSnapshotRepoRoot;
import org.opensearch.migrations.bulkload.common.GcsRepo.CannotListObjects;
import org.opensearch.migrations.bulkload.common.GcsRepo.CantWriteLocalFile;
import org.opensearch.migrations.bulkload.common.GcsRepo.CouldNotReadFromGcs;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class GcsRepoTest {
    @Mock
    private Storage mockStorage;
    @Mock
    private SnapshotFileFinder mockFileFinder;
    private TestableGcsRepo testRepo;
    @TempDir
    private Path testDir;
    private GcsUri testRepoUri = new GcsUri("gs://bucket-name/directory");
    private String testRepoFileName = "index-2";

    class TestableGcsRepo extends GcsRepo {
        public TestableGcsRepo(Path localDir, GcsUri gcsRepoUri, Storage storageClient, SnapshotFileFinder fileFinder) {
            super(localDir, gcsRepoUri, storageClient, fileFinder);
        }

        @Override
        protected boolean doesFileExistLocally(Path path) {
            return false;
        }

        @Override
        protected List<String> listFilesInRoot() {
            return super.listFilesInRoot();
        }

        @Override
        protected GcsUri makeGcsUri(Path filePath) {
            return super.makeGcsUri(filePath);
        }
    }

    @BeforeEach
    void setUp() {
        testRepo = Mockito.spy(new TestableGcsRepo(testDir, testRepoUri, mockStorage, mockFileFinder));
    }

    @Test
    void GetRepoRootDir_AsExpected() {
        Path filePath = testRepo.getRepoRootDir();
        assertEquals(testDir, filePath);
    }

    @Test
    void GetSnapshotRepoDataFilePath_AsExpected() {
        Path expectedPath = testDir.resolve(testRepoFileName);
        when(mockFileFinder.getSnapshotRepoDataFilePath(eq(testDir), any())).thenReturn(expectedPath);
        doReturn(List.of(testRepoFileName)).when(testRepo).listFilesInRoot();

        Path result = testRepo.getSnapshotRepoDataFilePath();

        assertEquals(expectedPath, result);
        verify(mockStorage).downloadTo(any(BlobId.class), any(OutputStream.class));
    }

    @Test
    void GetGlobalMetadataFilePath_AsExpected() {
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("meta-" + snapshotId + ".dat");
        when(mockFileFinder.getGlobalMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);

        Path result = testRepo.getGlobalMetadataFilePath(snapshotId);

        assertEquals(expectedPath, result);
        verify(mockStorage).downloadTo(any(BlobId.class), any(OutputStream.class));
    }

    @Test
    void GetSnapshotMetadataFilePath_AsExpected() {
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("snap-" + snapshotId + ".dat");
        when(mockFileFinder.getSnapshotMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);

        Path result = testRepo.getSnapshotMetadataFilePath(snapshotId);

        assertEquals(expectedPath, result);
    }

    @Test
    void GetIndexMetadataFilePath_AsExpected() {
        String indexId = "idx1";
        String indexFileId = "file1";
        Path expectedPath = testDir.resolve("indices/" + indexId + "/" + indexFileId);
        when(mockFileFinder.getIndexMetadataFilePath(testDir, indexId, indexFileId)).thenReturn(expectedPath);

        Path result = testRepo.getIndexMetadataFilePath(indexId, indexFileId);

        assertEquals(expectedPath, result);
    }

    @Test
    void GetShardDirPath_DoesNotDownload() {
        String indexId = "idx1";
        int shardId = 0;
        Path expectedPath = testDir.resolve("indices/" + indexId + "/" + shardId);
        when(mockFileFinder.getShardDirPath(testDir, indexId, shardId)).thenReturn(expectedPath);

        Path result = testRepo.getShardDirPath(indexId, shardId);

        assertEquals(expectedPath, result);
        verifyNoInteractions(mockStorage);
    }

    @Test
    void MakeGcsUri_CorrectlyMapsLocalPathToGcsUri() {
        Path localFile = testDir.resolve("indices/idx1/meta.dat");
        GcsUri result = testRepo.makeGcsUri(localFile);
        assertEquals("gs://bucket-name/directory/indices/idx1/meta.dat", result.uri);
    }

    @Test
    void MakeGcsUri_ThrowsForPathOutsideLocalDir() {
        Path outsidePath = Paths.get("/other/place/file.dat");
        assertThrows(IllegalArgumentException.class, () -> testRepo.makeGcsUri(outsidePath));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ListFilesInRoot_ThrowsWhenEmpty() {
        Page<Blob> emptyPage = mock(Page.class);
        when(emptyPage.iterateAll()).thenReturn(List.of());
        when(mockStorage.list(eq("bucket-name"), any(BlobListOption[].class))).thenReturn(emptyPage);

        var exception = assertThrows(CannotFindSnapshotRepoRoot.class, () -> testRepo.listFilesInRoot());
        assertThat(exception.getMessage(), containsString("bucket-name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ListFilesInRoot_ReturnsStrippedFileNames() {
        Blob blob1 = mock(Blob.class);
        when(blob1.getName()).thenReturn("directory/index-0");
        Blob blob2 = mock(Blob.class);
        when(blob2.getName()).thenReturn("directory/index-1");

        Page<Blob> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(List.of(blob1, blob2));
        when(mockStorage.list(eq("bucket-name"), any(BlobListOption[].class))).thenReturn(page);

        List<String> result = testRepo.listFilesInRoot();

        assertEquals(List.of("index-0", "index-1"), result);
    }

    @Test
    void CannotFindSnapshotRepoRoot_IsSnapshotReadFailure() {
        var exception = new CannotFindSnapshotRepoRoot("bucket-name", "directory");
        assertThat(exception, instanceOf(SnapshotReadFailure.class));
    }

    @Test
    void CannotListObjects_IsSnapshotReadFailure() {
        var exception = new CannotListObjects("bucket-name", "directory", new RuntimeException("cause"));
        assertThat(exception, instanceOf(SnapshotReadFailure.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 503})
    void Download_ServiceFailureIsClassifiedAndLeavesNoFile(int code) {
        var serviceError = new StorageException(code, "service error " + code);
        doThrow(serviceError).when(mockStorage).downloadTo(any(BlobId.class), any(OutputStream.class));
        Path localPath = testDir.resolve("meta-snap1.dat");
        when(mockFileFinder.getGlobalMetadataFilePath(testDir, "snap1")).thenReturn(localPath);

        var exception = assertThrows(CouldNotReadFromGcs.class, () -> testRepo.getGlobalMetadataFilePath("snap1"));

        assertThat(SnapshotReadFailures.find(exception), not(nullValue()));
        assertSame(serviceError, exception.getCause());
        assertThat(exception.getMessage(), containsString("bucket-name"));
        assertThat(exception.getMessage(), containsString("directory/meta-snap1.dat"));
        assertFalse(Files.exists(localPath));
    }

    @Test
    void Download_LocalWriteFailureIsNotASnapshotReadFailure() throws Exception {
        Path localPath = testDir.resolve("meta-snap1.dat");
        Files.createDirectory(localPath);
        when(mockFileFinder.getGlobalMetadataFilePath(testDir, "snap1")).thenReturn(localPath);

        var exception = assertThrows(CantWriteLocalFile.class, () -> testRepo.getGlobalMetadataFilePath("snap1"));

        assertThat(SnapshotReadFailures.find(exception), nullValue());
        verifyNoInteractions(mockStorage);
    }
}
