package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.bulkload.common.S3Repo.CannotFindSnapshotRepoRoot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class S3RepoTest {
    @Mock
    private S3AsyncClient mockS3Client;
    private TestableS3Repo testRepo;
    private Path testDir = Paths.get("/fake/path");
    private String testRegion = "us-fake-1";
    private S3Uri testRepoUri = new S3Uri("s3://bucket-name/directory");
    private String testRepoFileName = "index-2";
    private S3Uri testRepoFileUri = new S3Uri(testRepoUri.uri + "/" + testRepoFileName);

    @Mock
    private SnapshotFileFinder mockFileFinder;

    class TestableS3Repo extends S3Repo {
        public TestableS3Repo(Path s3LocalDir, S3Uri s3RepoUri, String s3Region, S3AsyncClient s3Client, SnapshotFileFinder fileFinder) {
            super(s3LocalDir, s3RepoUri, s3Region, s3Client, fileFinder);
        }

        @Override
        protected boolean doesFileExistLocally(Path path) {
            return false;
        }

        @Override
        protected List<String> listFilesInS3Root() {
            return super.listFilesInS3Root();
        }
    }

    @BeforeEach
    void setUp() {
        GetObjectResponse mockResponse = GetObjectResponse.builder().build();
        CompletableFuture<GetObjectResponse> noopFuture = CompletableFuture.completedFuture(mockResponse);
        lenient().when(mockS3Client.getObject(any(GetObjectRequest.class), any(AsyncResponseTransformer.class)))
                .thenReturn(noopFuture);

        testRepo = Mockito.spy(new TestableS3Repo(testDir, testRepoUri, testRegion, mockS3Client, mockFileFinder));
    }

    @Test
    void GetRepoRootDir_AsExpected() throws IOException {
        // Run the test
        Path filePath = testRepo.getRepoRootDir();

        // Check the results
        assertEquals(testDir, filePath);
    }

    @Test
    void GetSnapshotRepoDataFilePath_AsExpected() throws IOException {
        // mock listFilesInS3Root() to return list of files
        doReturn(List.of(testRepoFileName)).when(testRepo).listFilesInS3Root();

        // Expected local path
        Path expectedPath = testDir.resolve(testRepoFileName);

        // fileFinder simply returns the resolved path
        when(mockFileFinder.getSnapshotRepoDataFilePath(eq(testDir), anyList()))
                .thenReturn(expectedPath);

        // file does not exist locally, so fetch() will download it
        doReturn(false).when(testRepo).doesFileExistLocally(expectedPath);

        // allow directory creation
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Run the test
        Path filePath = testRepo.getSnapshotRepoDataFilePath();

        // Check the results
        assertEquals(expectedPath, filePath);
        verify(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        String expectedKey = testRepo.makeS3Uri(expectedPath).key;

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(testRepoUri.bucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }


    @Test
    void GetSnapshotRepoDataFilePath_DoesNotExist() throws IOException {
        // Set up the test
        var listResponse = mock(ListObjectsV2Response.class);
        when(listResponse.contents()).thenReturn(List.of());
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(listResponse));

        // Spy on testRepo to call real listFilesInS3Root (which calls the above)
        TestableS3Repo testRepo = spy(new TestableS3Repo(testDir, testRepoUri, testRegion, mockS3Client, mockFileFinder));

        // Mock fileFinder to throw exception when asked to find the path with empty file list
        var emptyFileList = List.<String>of();
        when(mockFileFinder.getSnapshotRepoDataFilePath(eq(testDir), eq(emptyFileList)))
                .thenThrow(new BaseSnapshotFileFinder.CannotFindRepoIndexFile("No matching index-N file found"));

        // Run the test
        CannotFindSnapshotRepoRoot thrown = assertThrows(
                CannotFindSnapshotRepoRoot.class,
                () -> testRepo.getSnapshotRepoDataFilePath()
        );

        // Check the results
        assertThat(thrown.getMessage(), containsString(testRepoUri.bucketName));
        assertThat(thrown.getMessage(), containsString(testRepoUri.key));

    }

    @Test
    void GetGlobalMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String snapshotId = "snapshot1";
        String metadataFileName = "meta-" + snapshotId + ".dat";
        Path expectedPath = testDir.resolve(metadataFileName);
        String expectedBucketName = testRepoUri.bucketName;

        // Mock the fileFinder to return the expected local path
        when(mockFileFinder.getGlobalMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        // Stub ensureS3LocalDirectoryExists to no-op
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Run the test
        Path filePath = testRepo.getGlobalMetadataFilePath(snapshotId);

        // Check the results
        assertEquals(expectedPath, filePath);

        // check directory preparation was called
        verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Derive the expected S3 key based on s3RepoUri and relative path
        String expectedKey = testRepo.makeS3Uri(expectedPath).key;

        // Verify the S3 client was called with the correct bucket and key for downloads
        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(expectedBucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }

    @Test
    void GetSnapshotMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String snapshotId = "snapshot1";
        String snapshotFileName = "snap-" + snapshotId + ".dat";
        Path expectedPath = testDir.resolve(snapshotFileName);

        String expectedBucketName = testRepoUri.bucketName;
        String expectedKey = testRepoUri.key + "/" + snapshotFileName;

        // Mock the fileFinder method returning expected local path
        when(mockFileFinder.getGlobalMetadataFilePath(testDir, snapshotId)).thenReturn(expectedPath);
        // Stub ensureS3LocalDirectoryExists to no-op
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Act
        Path filePath = testRepo.getGlobalMetadataFilePath(snapshotId);

        // Assert
        assertEquals(expectedPath, filePath);

        verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(expectedBucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }

    @Test
    void GetIndexMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String indexId = "123abc";
        String indexFileId = "234bcd";
        String indexFileName = "indices/" + indexId + "/meta-" + indexFileId + ".dat";
        Path expectedPath = testDir.resolve(indexFileName);

        String expectedBucketName = testRepoUri.bucketName;
        String expectedKey = testRepoUri.key + "/" + indexFileName;

        // Mock the fileFinder method returning expected local path
        when(mockFileFinder.getIndexMetadataFilePath(testDir, indexId, indexFileId)).thenReturn(expectedPath);
        // Stub ensureS3LocalDirectoryExists to no-op
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Run the test
        Path filePath = testRepo.getIndexMetadataFilePath(indexId, indexFileId);

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(expectedBucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }

    @Test
    void GetShardDirPath_AsExpected() throws IOException {
        // Set up the test
        String indexId = "123abc";
        int shardId = 7;
        String shardDirName = "indices/" + indexId + "/" + shardId;
        Path expectedPath = testDir.resolve(shardDirName);

        // Mock the fileFinder to return expected path
        when(mockFileFinder.getShardDirPath(testDir, indexId, shardId)).thenReturn(expectedPath);

        // Run the test
        Path filePath = testRepo.getShardDirPath(indexId, shardId);

        // Check the results
        assertEquals(expectedPath, filePath);
    }

    @Test
    void GetShardMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String snapshotId = "snapshot1";
        String indexId = "123abc";
        int shardId = 7;
        String shardFileName = "indices/" + indexId + "/" + shardId + "/snap-" + snapshotId + ".dat";
        Path expectedPath = testDir.resolve(shardFileName);

        String expectedBucketName = testRepoUri.bucketName;
        String expectedKey = testRepoUri.key + "/" + shardFileName;

        // Mock the fileFinder behavior to return the expected local path
        when(mockFileFinder.getShardMetadataFilePath(testDir, snapshotId, indexId, shardId)).thenReturn(expectedPath);
        // Stub ensureS3LocalDirectoryExists to no-op
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Run the test
        Path filePath = testRepo.getShardMetadataFilePath(snapshotId, indexId, shardId);

        // Check the results
        assertEquals(expectedPath, filePath);
        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(expectedBucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }

    @Test
    void GetBlobFilePath_AsExpected() throws IOException {
        // Set up the test
        String blobName = "bobloblaw";
        String indexId = "123abc";
        int shardId = 7;
        String blobFileName = "indices/" + indexId + "/" + shardId + "/" + blobName;
        Path expectedPath = testDir.resolve(blobFileName);

        String expectedBucketName = testRepoUri.bucketName;
        String expectedKey = testRepoUri.key + "/" + blobFileName;

        // Mock the SnapshotFileFinder method to return expected local path
        when(mockFileFinder.getBlobFilePath(testDir, indexId, shardId, blobName)).thenReturn(expectedPath);
        // Stub ensureS3LocalDirectoryExists to no-op
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Run the test
        Path filePath = testRepo.getBlobFilePath(indexId, shardId, blobName);

        // Check the results
        assertEquals(expectedPath, filePath);
        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(expectedBucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }

    @Test
    void listFilesInS3Root_ReturnsStrippedKeys() throws IOException {
        // Mock S3 response with some keys under the prefix "directory/"
        ListObjectsV2Response response = ListObjectsV2Response.builder()
            .contents(List.of(
                S3Object.builder().key("directory/file1.txt").build(),
                S3Object.builder().key("directory/file2.txt").build(),
                // Adding a file outside prefix to verify filtering logic
                S3Object.builder().key("other/file3.txt").build()
            ))
            .build();

        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        // Assuming TestableS3Repo should have s3RepoUri.key = "directory" for this to work properly
        List<String> files = testRepo.listFilesInS3Root();

        // only files under that prefix returned, others excluded
        assertEquals(List.of("file1.txt", "file2.txt"), files);
    }

    @Test
    void getSnapshotRepoDataFilePath_WithEmptyFileName() throws IOException {
        // Create empty file name so no file in the root prefix
        String emptyFileName = "";

        // Mock listFilesInS3Root to return one file which is empty string
        doReturn(List.of(emptyFileName)).when(testRepo).listFilesInS3Root();

        // The expected path is just the local directory root
        Path expectedPath = testDir.resolve(emptyFileName);

        // Mock fileFinder to return the root path for empty file name
        when(mockFileFinder.getSnapshotRepoDataFilePath(testDir, List.of(emptyFileName)))
                .thenReturn(expectedPath);

        // Assume file does not exist locally, so fetch will attempt download
        doReturn(false).when(testRepo).doesFileExistLocally(expectedPath);

        // Stub directory creation as no-op
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Run the test
        Path filePath = testRepo.getSnapshotRepoDataFilePath();

        // Assertions
        assertEquals(expectedPath, filePath);
        verify(testRepo).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // Expected key from makeS3Uri - since empty filename, key is repo prefix itself without trailing slash
        String expectedKey = testRepo.makeS3Uri(expectedPath).key;

        // Verify that S3 client invoked with bucket and correct key for download
        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(testRepoUri.bucketName)
                .key(expectedKey)
                .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(AsyncResponseTransformer.class));
    }
}
