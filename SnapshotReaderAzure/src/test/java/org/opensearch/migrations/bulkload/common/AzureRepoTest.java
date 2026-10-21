package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.bulkload.common.AzureRepo.CannotFindSnapshotRepoRoot;
import org.opensearch.migrations.bulkload.common.AzureRepo.CannotListBlobs;
import org.opensearch.migrations.bulkload.common.AzureRepo.CouldNotReadFromAzure;

import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AzureRepoTest {
    @Mock
    private BlobContainerClient mockContainerClient;
    @Mock
    private BlobClient mockBlobClient;
    @Mock
    private SnapshotFileFinder mockFileFinder;
    private TestableAzureRepo testRepo;
    private Path testDir = Paths.get("/fake/path");
    private AzureUri testRepoUri = new AzureUri("azure://myaccount/container-name/directory");
    private String testRepoFileName = "index-2";

    class TestableAzureRepo extends AzureRepo {
        public TestableAzureRepo(
            Path localDir,
            AzureUri azureRepoUri,
            BlobContainerClient containerClient,
            SnapshotFileFinder fileFinder
        ) {
            super(localDir, azureRepoUri, containerClient, fileFinder);
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
        protected AzureUri makeAzureUri(Path filePath) {
            return super.makeAzureUri(filePath);
        }
    }

    @BeforeEach
    void setUp() {
        lenient().when(mockContainerClient.getBlobClient(anyString())).thenReturn(mockBlobClient);

        testRepo = Mockito.spy(
            new TestableAzureRepo(testDir, testRepoUri, mockContainerClient, mockFileFinder)
        );
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
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));

        Path result = testRepo.getSnapshotRepoDataFilePath();

        assertEquals(expectedPath, result);
        verify(mockContainerClient).getBlobClient(anyString());
    }

    @Test
    void GetGlobalMetadataFilePath_AsExpected() {
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("meta-" + snapshotId + ".dat");
        when(mockFileFinder.getGlobalMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));

        Path result = testRepo.getGlobalMetadataFilePath(snapshotId);

        assertEquals(expectedPath, result);
        verify(mockContainerClient).getBlobClient(anyString());
    }

    @Test
    void GetSnapshotMetadataFilePath_AsExpected() {
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("snap-" + snapshotId + ".dat");
        when(mockFileFinder.getSnapshotMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));

        Path result = testRepo.getSnapshotMetadataFilePath(snapshotId);

        assertEquals(expectedPath, result);
    }

    @Test
    void GetIndexMetadataFilePath_AsExpected() {
        String indexId = "idx1";
        String indexFileId = "file1";
        Path expectedPath = testDir.resolve("indices/" + indexId + "/" + indexFileId);
        when(mockFileFinder.getIndexMetadataFilePath(testDir, indexId, indexFileId)).thenReturn(expectedPath);
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));

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
        verifyNoInteractions(mockContainerClient);
    }

    @Test
    void MakeAzureUri_CorrectlyMapsLocalPathToAzureUri() {
        Path localFile = testDir.resolve("indices/idx1/meta.dat");
        AzureUri result = testRepo.makeAzureUri(localFile);
        assertEquals(
            "azure://myaccount/container-name/directory/indices/idx1/meta.dat",
            result.uri
        );
    }

    @Test
    void MakeAzureUri_ThrowsForPathOutsideLocalDir() {
        Path outsidePath = Paths.get("/other/place/file.dat");
        assertThrows(IllegalArgumentException.class, () -> testRepo.makeAzureUri(outsidePath));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ListFilesInRoot_ThrowsWhenEmpty() {
        PagedIterable<BlobItem> emptyPage = mock(PagedIterable.class);
        when(emptyPage.iterator()).thenReturn(List.<BlobItem>of().iterator());
        when(mockContainerClient.listBlobsByHierarchy(
            eq("/"), any(ListBlobsOptions.class), nullable(Duration.class))
        ).thenReturn(emptyPage);

        var exception = assertThrows(CannotFindSnapshotRepoRoot.class, () -> testRepo.listFilesInRoot());
        assertThat(exception.getMessage(), containsString("container-name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ListFilesInRoot_ReturnsStrippedFileNames() {
        BlobItem blob1 = mock(BlobItem.class);
        when(blob1.getName()).thenReturn("directory/index-0");
        BlobItem blob2 = mock(BlobItem.class);
        when(blob2.getName()).thenReturn("directory/index-1");

        PagedIterable<BlobItem> page = mock(PagedIterable.class);
        when(page.iterator()).thenReturn(List.of(blob1, blob2).iterator());
        when(mockContainerClient.listBlobsByHierarchy(
            eq("/"), any(ListBlobsOptions.class), nullable(Duration.class))
        ).thenReturn(page);

        List<String> result = testRepo.listFilesInRoot();

        assertEquals(List.of("index-0", "index-1"), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ListFilesInRoot_SkipsPrefixEntries() {
        BlobItem file = mock(BlobItem.class);
        when(file.getName()).thenReturn("directory/index-0");
        BlobItem prefix = mock(BlobItem.class);
        when(prefix.isPrefix()).thenReturn(true);

        PagedIterable<BlobItem> page = mock(PagedIterable.class);
        when(page.iterator()).thenReturn(List.of(prefix, file).iterator());
        when(mockContainerClient.listBlobsByHierarchy(
            eq("/"), any(ListBlobsOptions.class), nullable(Duration.class))
        ).thenReturn(page);

        List<String> result = testRepo.listFilesInRoot();

        assertEquals(List.of("index-0"), result);
    }

    @Test
    void ListFilesInRoot_WrapsBlobStorageExceptionInCannotListBlobs() {
        // A missing container or a denied listing surfaces as BlobStorageException from the SDK.
        // AzureRepo must repackage it as CannotListBlobs so the worker classifies it as a
        // snapshot read failure and reports the container it failed against.
        BlobStorageException azureFailure = new BlobStorageException(
            "ContainerNotFound", mock(HttpResponse.class), null);
        when(mockContainerClient.listBlobsByHierarchy(
            eq("/"), any(ListBlobsOptions.class), nullable(Duration.class))
        ).thenThrow(azureFailure);

        var thrown = assertThrows(CannotListBlobs.class, () -> testRepo.listFilesInRoot());

        assertThat(thrown.getCause(), sameInstance(azureFailure));
        assertThat(thrown.getMessage(), containsString("container-name"));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 503})
    void EnsureFileExistsLocally_WrapsBlobStorageExceptionInCouldNotReadFromAzure(int statusCode) {
        // Denied access, a blob missing on the service, and a service error that exhausted the
        // client's retries all surface from the download as BlobStorageException. Each must be
        // classified as a snapshot read failure that keeps the service error as its cause.
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("snap-" + snapshotId + ".dat");
        when(mockFileFinder.getSnapshotMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));
        HttpResponse response = mock(HttpResponse.class);
        lenient().when(response.getStatusCode()).thenReturn(statusCode);
        BlobStorageException azureFailure = new BlobStorageException("service failure", response, null);
        when(mockBlobClient.downloadToFile(anyString(), Mockito.anyBoolean())).thenThrow(azureFailure);

        var thrown = assertThrows(
            CouldNotReadFromAzure.class,
            () -> testRepo.getSnapshotMetadataFilePath(snapshotId)
        );

        assertThat(thrown, instanceOf(SnapshotReadFailure.class));
        assertThat(thrown.getCause(), sameInstance(azureFailure));
        assertThat(thrown.getMessage(), containsString("container-name"));
        assertThat(thrown.getMessage(), containsString("directory/snap-snap1.dat"));
    }

    @Test
    void EnsureFileExistsLocally_LeavesLocalFilesystemFailuresUnclassified() {
        // A failure writing the downloaded file is a local problem, not a problem reading the
        // snapshot, so it must not be reported as a snapshot read failure.
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("snap-" + snapshotId + ".dat");
        when(mockFileFinder.getSnapshotMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));
        UncheckedIOException localFailure = new UncheckedIOException(new IOException("disk full"));
        when(mockBlobClient.downloadToFile(anyString(), Mockito.anyBoolean())).thenThrow(localFailure);

        var thrown = assertThrows(
            UncheckedIOException.class,
            () -> testRepo.getSnapshotMetadataFilePath(snapshotId)
        );

        assertThat(thrown, sameInstance(localFailure));
        assertNull(SnapshotReadFailures.find(thrown));
    }

    @Test
    void GetSnapshotMetadataFilePath_DownloadsExpectedBlobKey() {
        String snapshotId = "snap1";
        Path expectedPath = testDir.resolve("snap-" + snapshotId + ".dat");
        when(mockFileFinder.getSnapshotMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        doNothing().when(testRepo).ensureLocalDirectoryExists(any(Path.class));

        testRepo.getSnapshotMetadataFilePath(snapshotId);

        // The blob key is the repo prefix joined with the path relative to the local dir, and the
        // download overwrites any partial local file.
        verify(mockContainerClient).getBlobClient("directory/snap-snap1.dat");
        verify(mockBlobClient).downloadToFile("/fake/path/snap-snap1.dat", true);
    }

    @Test
    void GetSnapshotRepoDataFilePath_TranslatesCannotFindRepoIndexFile() {
        doReturn(List.of()).when(testRepo).listFilesInRoot();
        when(mockFileFinder.getSnapshotRepoDataFilePath(eq(testDir), eq(List.of())))
            .thenThrow(new BaseSnapshotFileFinder.CannotFindRepoIndexFile("No matching index-N file found"));

        var thrown = assertThrows(
            CannotFindSnapshotRepoRoot.class,
            () -> testRepo.getSnapshotRepoDataFilePath()
        );

        assertThat(thrown.getMessage(), containsString(testRepoUri.containerName));
        assertThat(thrown.getMessage(), containsString(testRepoUri.key));
    }

    @Test
    void CannotFindSnapshotRepoRoot_IsSnapshotReadFailure() {
        var exception = new CannotFindSnapshotRepoRoot("container-name", "directory");
        assertThat(exception, instanceOf(SnapshotReadFailure.class));
    }

    @Test
    void CannotListBlobs_IsSnapshotReadFailure() {
        var exception = new CannotListBlobs("container-name", "directory", new RuntimeException("cause"));
        assertThat(exception, instanceOf(SnapshotReadFailure.class));
    }
}
