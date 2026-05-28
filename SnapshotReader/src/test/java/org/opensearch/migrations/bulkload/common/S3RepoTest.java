package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.bulkload.common.S3Repo.CannotFindSnapshotRepoRoot;
import org.opensearch.migrations.bulkload.common.S3Repo.CannotListObjectsInS3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(listResponse));

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
                S3Object.builder().key("directory/foo/fooagain/file3.txt").build()
            ))
            .build();

        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        // Assuming TestableS3Repo should have s3RepoUri.key = "directory" for this to work properly
        List<String> files = testRepo.listFilesInS3Root();

        // Only files under that prefix returned, others excluded
        assertEquals(List.of("file1.txt", "file2.txt"), files);
    }

    @Test
    void getSnapshotRepoDataFilePath_WithEmptyFileName() throws IOException {
        // Mock listFilesInS3Root to return one file which is empty string
        doReturn(List.of()).when(testRepo).listFilesInS3Root();

        // Mock fileFinder to throw the expected exception because no files are found
        when(mockFileFinder.getSnapshotRepoDataFilePath(eq(testDir), eq(List.of())))
                .thenThrow(new BaseSnapshotFileFinder.CannotFindRepoIndexFile("No matching index-N file found"));

        // Run and assert that the S3Repo throws CannotFindSnapshotRepoRoot when no index file is found
        CannotFindSnapshotRepoRoot thrown = assertThrows(
                CannotFindSnapshotRepoRoot.class,
                () -> testRepo.getSnapshotRepoDataFilePath()
        );

        // Assertions
        assertThat(thrown.getMessage(), containsString(testRepoUri.bucketName));
        assertThat(thrown.getMessage(), containsString(testRepoUri.key));
    }

    // ---------- close ----------------------------------------------------

    @Test
    void close_DelegatesToS3Client() {
        testRepo.close();
        verify(mockS3Client, times(1)).close();
    }

    @Test
    void close_WithNullClient_IsNoOp() {
        // When the repo was constructed without an S3 client (defensive code
        // path), close() must not NPE — it just has nothing to do.
        var nullClientRepo = new TestableS3Repo(testDir, testRepoUri, testRegion, null, mockFileFinder);
        assertDoesNotThrow(nullClientRepo::close);
    }

    // ---------- toString -------------------------------------------------

    @Test
    void toString_EmbedsUriAndRegion() {
        String s = testRepo.toString();
        assertThat(s, containsString(testRepoUri.uri));
        assertThat(s, containsString(testRegion));
    }

    // ---------- listFilesInS3Root error path -----------------------------

    @Test
    void listFilesInS3Root_WrapsCompletionExceptionAsCannotListObjects() {
        // Simulate an S3 client failure — the joined future raises CompletionException
        // which S3Repo must repackage as CannotListObjectsInS3 with bucket + prefix
        // context so operators can identify the failed call.
        CompletableFuture<ListObjectsV2Response> failed = new CompletableFuture<>();
        failed.completeExceptionally(new CompletionException(new RuntimeException("AccessDenied")));
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(failed);

        CannotListObjectsInS3 thrown = assertThrows(
            CannotListObjectsInS3.class,
            () -> testRepo.listFilesInS3Root()
        );
        assertThat(thrown.getMessage(), containsString(testRepoUri.bucketName));
        // Strip the trailing slash like the production code does before reporting.
        assertThat(thrown.getMessage(), containsString(testRepoUri.key));
    }

    // ---------- listSubDirectories / listTopLevelDirectories -------------

    @Test
    void listSubDirectories_ReturnsCommonPrefixNamesStrippedOfRepoPrefixAndSlash() {
        // Top-level "directories" under s3://bucket-name/directory/ — common
        // prefixes come back as full keys ending with "/"; the helper should
        // strip the repo prefix AND the trailing slash so callers get just the
        // collection names.
        ListObjectsV2Response response = ListObjectsV2Response.builder()
            .commonPrefixes(
                CommonPrefix.builder().prefix("directory/collA/").build(),
                CommonPrefix.builder().prefix("directory/collB/").build()
            )
            .build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        List<String> dirs = testRepo.listSubDirectories("");
        assertThat(dirs, contains("collA", "collB"));
    }

    @Test
    void listSubDirectories_FiltersEmptyEntries() {
        // A common prefix that equals the listed prefix (i.e. the listing
        // includes the "directory" entry for the prefix itself) should be
        // filtered out — we want subdirectory names only.
        ListObjectsV2Response response = ListObjectsV2Response.builder()
            .commonPrefixes(
                CommonPrefix.builder().prefix("directory/").build(),         // -> "" after strip
                CommonPrefix.builder().prefix("directory/keepMe/").build()
            )
            .build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        assertThat(testRepo.listSubDirectories(""), contains("keepMe"));
    }

    @Test
    void listSubDirectories_RelativePrefix_IsJoinedAndSlashTerminated() {
        // The caller passes "collA" (no trailing slash) and the helper should
        // join it with the repo's prefix and ensure a single trailing slash.
        ListObjectsV2Response response = ListObjectsV2Response.builder()
            .commonPrefixes(CommonPrefix.builder().prefix("directory/collA/index/").build())
            .build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);

        List<String> result = testRepo.listSubDirectories("collA");
        assertThat(result, contains("index"));

        verify(mockS3Client).listObjectsV2(captor.capture());
        ListObjectsV2Request request = captor.getValue();
        assertEquals(testRepoUri.bucketName, request.bucket());
        assertEquals("directory/collA/", request.prefix());
        assertEquals("/", request.delimiter());
    }

    @Test
    void listSubDirectories_EmptyResponseReturnsEmptyList() {
        ListObjectsV2Response response = ListObjectsV2Response.builder().build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        assertThat(testRepo.listSubDirectories(""), is(empty()));
    }

    @Test
    void listSubDirectories_CompletionExceptionWrapsAsCannotListObjects() {
        CompletableFuture<ListObjectsV2Response> failed = new CompletableFuture<>();
        failed.completeExceptionally(new CompletionException(new RuntimeException("throttled")));
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(failed);

        CannotListObjectsInS3 thrown = assertThrows(
            CannotListObjectsInS3.class,
            () -> testRepo.listSubDirectories("collA")
        );
        assertThat(thrown.getMessage(), containsString(testRepoUri.bucketName));
        // Joined prefix should appear so the operator can reproduce the call.
        assertThat(thrown.getMessage(), containsString("directory/collA/"));
    }

    @Test
    void listTopLevelDirectories_DelegatesToListSubDirectoriesWithEmptyPrefix() {
        // testRepo is already a spy; stub on it directly.
        doReturn(List.of("a", "b")).when(testRepo).listSubDirectories("");

        List<String> result = testRepo.listTopLevelDirectories();
        assertThat(result, contains("a", "b"));
        verify(testRepo).listSubDirectories("");
    }

    // ---------- downloadFile / downloadAllFiles / downloadPrefix ---------

    @Test
    void downloadFile_ResolvesUnderRepoRootAndFetches() {
        // ensureS3LocalDirectoryExists would otherwise hit the real filesystem at /fake/path.
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(any());

        Path localPath = testRepo.downloadFile("collA/index/abc123");
        assertEquals(testDir.resolve("collA/index/abc123"), localPath);

        // getObject was issued with bucket/key matching the joined URI.
        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(mockS3Client).getObject(req.capture(), any(AsyncResponseTransformer.class));
        assertEquals(testRepoUri.bucketName, req.getValue().bucket());
        assertEquals("directory/collA/index/abc123", req.getValue().key());
    }

    @Test
    void downloadAllFiles_DelegatesToDownloadPrefixWithEmptyString() {
        // Stub on the spy directly — Mockito.spy on a spy preserves the
        // underlying mock state but stubs are scoped to the new wrapper.
        doReturn(testDir).when(testRepo).downloadPrefix("");

        Path result = testRepo.downloadAllFiles();
        assertThat(result, sameInstance(testDir));
        verify(testRepo).downloadPrefix("");
    }

    @Test
    void downloadPrefix_DownloadsEveryObjectAndSkipsDirectoryMarkers() {
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(any());

        // S3 listings sometimes include synthetic "directory marker" entries
        // (keys ending in "/") and the prefix itself. Neither should be
        // downloaded — only real file entries.
        ListObjectsV2Response response = ListObjectsV2Response.builder()
            .contents(List.of(
                S3Object.builder().key("directory/collA/").build(),         // skipped (empty rel-path)
                S3Object.builder().key("directory/collA/sub/").build(),    // skipped (trailing slash)
                S3Object.builder().key("directory/collA/file1.bin").build(),
                S3Object.builder().key("directory/collA/file2.bin").build()
            ))
            .isTruncated(false)
            .build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(CompletableFuture.completedFuture(response));

        Path result = testRepo.downloadPrefix("collA");
        assertThat(result, sameInstance(testDir));

        // Exactly 2 downloads (for the file entries), not 4.
        verify(mockS3Client, times(2)).getObject(
            any(GetObjectRequest.class), any(AsyncResponseTransformer.class));
    }

    @Test
    void downloadPrefix_HonoursPaginationContinuationToken() {
        doNothing().when(testRepo).ensureS3LocalDirectoryExists(any());

        // First page is truncated and carries a continuation token; second page
        // returns the rest. The helper must call listObjectsV2 twice and pass
        // the token on the second call.
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
            .contents(List.of(S3Object.builder().key("directory/collA/p1.bin").build()))
            .isTruncated(true)
            .nextContinuationToken("tok-1")
            .build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
            .contents(List.of(S3Object.builder().key("directory/collA/p2.bin").build()))
            .isTruncated(false)
            .build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class)))
            .thenReturn(CompletableFuture.completedFuture(page1))
            .thenReturn(CompletableFuture.completedFuture(page2));

        testRepo.downloadPrefix("collA");

        ArgumentCaptor<ListObjectsV2Request> requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(mockS3Client, times(2)).listObjectsV2(requests.capture());

        // First call: no token. Second call: token from page1.
        assertEquals(null, requests.getAllValues().get(0).continuationToken());
        assertEquals("tok-1", requests.getAllValues().get(1).continuationToken());

        // One getObject per real file across both pages.
        verify(mockS3Client, times(2)).getObject(
            any(GetObjectRequest.class), any(AsyncResponseTransformer.class));
    }

    @Test
    void downloadPrefix_CompletionExceptionWrapsAsCannotListObjects() {
        CompletableFuture<ListObjectsV2Response> failed = new CompletableFuture<>();
        failed.completeExceptionally(new CompletionException(new RuntimeException("503")));
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(failed);

        CannotListObjectsInS3 thrown = assertThrows(
            CannotListObjectsInS3.class,
            () -> testRepo.downloadPrefix("collA")
        );
        assertThat(thrown.getMessage(), containsString(testRepoUri.bucketName));
        assertThat(thrown.getMessage(), containsString("directory/collA/"));
    }
}
