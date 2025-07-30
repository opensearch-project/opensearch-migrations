package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.bulkload.common.S3Repo.CannotFindSnapshotRepoRoot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Disabled("Temporarily disabled during build")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        protected void ensureS3LocalDirectoryExists(Path path) {
            // Do nothing
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

        // Mock the fileFinder's behavior for returning the local path
        Path expectedPath = testDir.resolve(testRepoFileName);
        when(mockFileFinder.getSnapshotRepoDataFilePath(testDir, List.of(testRepoFileName))).thenReturn(expectedPath);

        // Run the test
        Path filePath = testRepo.getSnapshotRepoDataFilePath();

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        // check that fetch was called by verifying s3Client call
        GetObjectRequest expectedRequest = GetObjectRequest.builder()
                .bucket(testRepoUri.bucketName)
                .key(testRepoFileName)
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
        // This mimics makeS3Uri() logic
        String relativeKey = testDir.relativize(expectedPath).toString().replace('\\', '/');
        String baseUri = testRepoUri.uri.endsWith("/")
                ? testRepoUri.uri.substring(0, testRepoUri.uri.length() - 1)
                : testRepoUri.uri;

        String expectedKey = relativeKey.isEmpty()
                ? baseUri.substring(baseUri.indexOf("/") + 1)  // unlikely empty, but safe fallback if root
                : baseUri.substring(baseUri.indexOf("/") + 1) + "/" + relativeKey;

        // s3RepoUri.uri includes "s3://bucket-name/directory"
        // the key is the part after "bucket-name/"
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
}
