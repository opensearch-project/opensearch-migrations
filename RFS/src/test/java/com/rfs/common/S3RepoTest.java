package com.rfs.common;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
public class S3RepoTest {
    @Mock
    private S3Client mockS3Client;
    private TestableS3Repo testRepo;
    private Path testDir = Paths.get("/fake/path");
    private String testRegion = "us-fake-1";
    private String testUri = "s3://bucket-name/directory";
    private String testRepoFileName = "index-2";
    private String testRepoFileUri = testUri + "/" + testRepoFileName;
    

    class TestableS3Repo extends S3Repo {
        public TestableS3Repo(Path s3LocalDir, String s3RepoUri, String s3Region, S3Client s3Client) {
            super(s3LocalDir, s3RepoUri, s3Region, s3Client);
        }

        @Override
        protected void ensureS3LocalDirectoryExists(Path path) {
            // Do nothing
        }

        @Override
        protected String findRepoFileUri() {
            return testRepoFileUri;
        }
    }

    @BeforeEach
    void setUp() {
        testRepo = Mockito.spy(new TestableS3Repo(testDir, testUri, testRegion, mockS3Client));
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
        // Set up the test
        Path expectedPath = testDir.resolve(testRepoFileName);
        String expectedBucketName = testRepo.getS3BucketName();
        String expectedKey = testRepo.getS3ObjectsPrefix() + "/" + testRepoFileName;

        // Run the test
        Path filePath = testRepo.getSnapshotRepoDataFilePath();

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
            .bucket(expectedBucketName)
            .key(expectedKey)
            .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(ResponseTransformer.class));
    }


    @Test
    void GetGlobalMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String snapshotId = "snapshot1";
        String snapshotFileName = "meta-" + snapshotId + ".dat";
        Path expectedPath = testDir.resolve(snapshotFileName);
        String expectedBucketName = testRepo.getS3BucketName();
        String expectedKey = testRepo.getS3ObjectsPrefix() + "/" + snapshotFileName;

        // Run the test
        Path filePath = testRepo.getGlobalMetadataFilePath(snapshotId);

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
            .bucket(expectedBucketName)
            .key(expectedKey)
            .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(ResponseTransformer.class));
    }

    @Test
    void GetSnapshotMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String snapshotId = "snapshot1";
        String snapshotFileName = "snap-" + snapshotId + ".dat";
        Path expectedPath = testDir.resolve(snapshotFileName);
        String expectedBucketName = testRepo.getS3BucketName();
        String expectedKey = testRepo.getS3ObjectsPrefix() + "/" + snapshotFileName;

        // Run the test
        Path filePath = testRepo.getSnapshotMetadataFilePath(snapshotId);

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
            .bucket(expectedBucketName)
            .key(expectedKey)
            .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(ResponseTransformer.class));
    }

    @Test
    void GetIndexMetadataFilePath_AsExpected() throws IOException {
        // Set up the test
        String indexId = "123abc";
        String indexFileId = "234bcd";
        String indexFileName = "indices/" + indexId + "/meta-" + indexFileId + ".dat";
        Path expectedPath = testDir.resolve(indexFileName);
        String expectedBucketName = testRepo.getS3BucketName();
        String expectedKey = testRepo.getS3ObjectsPrefix() + "/" + indexFileName;

        // Run the test
        Path filePath = testRepo.getIndexMetadataFilePath(indexId, indexFileId);

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
            .bucket(expectedBucketName)
            .key(expectedKey)
            .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(ResponseTransformer.class));
    }

    @Test
    void GetShardDirPath_AsExpected() throws IOException {
        // Set up the test
        String indexId = "123abc";
        int shardId = 7;
        String shardDirName = "indices/" + indexId + "/" + shardId;
        Path expectedPath = testDir.resolve(shardDirName);

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
        String expectedBucketName = testRepo.getS3BucketName();
        String expectedKey = testRepo.getS3ObjectsPrefix() + "/" + shardFileName;

        // Run the test
        Path filePath = testRepo.getShardMetadataFilePath(snapshotId, indexId, shardId);

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
            .bucket(expectedBucketName)
            .key(expectedKey)
            .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(ResponseTransformer.class));
    }

    @Test
    void GetBlobFilePath_AsExpected() throws IOException {
        // Set up the test
        String blobName = "bobloblaw";
        String indexId = "123abc";
        int shardId = 7;
        String shardFileName = "indices/" + indexId + "/" + shardId + "/" + blobName;
        Path expectedPath = testDir.resolve(shardFileName);
        String expectedBucketName = testRepo.getS3BucketName();
        String expectedKey = testRepo.getS3ObjectsPrefix() + "/" + shardFileName;

        // Run the test
        Path filePath = testRepo.getBlobFilePath(indexId, shardId, blobName);

        // Check the results
        assertEquals(expectedPath, filePath);

        Mockito.verify(testRepo, times(1)).ensureS3LocalDirectoryExists(expectedPath.getParent());

        GetObjectRequest expectedRequest = GetObjectRequest.builder()
            .bucket(expectedBucketName)
            .key(expectedKey)
            .build();

        verify(mockS3Client).getObject(eq(expectedRequest), any(ResponseTransformer.class));
    }

    static Stream<Arguments> provideUrisForBucketNames() {
        return Stream.of(
            Arguments.of("s3://bucket-name", "bucket-name"),
            Arguments.of("s3://bucket-name/", "bucket-name"),
            Arguments.of("s3://bucket-name/with/suffix", "bucket-name")
        );
    }

    @ParameterizedTest
    @MethodSource("provideUrisForBucketNames")
    void getS3BucketName_AsExpected(String uri, String expectedBucketName) {
        TestableS3Repo repo = new TestableS3Repo(testDir, uri, testRegion, mock(S3Client.class));
        assertEquals(expectedBucketName, repo.getS3BucketName());
    }

    static Stream<Arguments> provideUrisForPrefixes() {
        return Stream.of(
            Arguments.of("s3://bucket-name", ""),
            Arguments.of("s3://bucket-name/", ""),
            Arguments.of("s3://bucket-name/with/suffix", "with/suffix"),
            Arguments.of("s3://bucket-name/with/suffix/", "with/suffix")
        );
    }

    @ParameterizedTest
    @MethodSource("provideUrisForPrefixes")
    void getS3ObjectsPrefix_AsExpected(String uri, String expectedPrefix) {
        TestableS3Repo repo = new TestableS3Repo(testDir, uri, testRegion, mock(S3Client.class));
        assertEquals(expectedPrefix, repo.getS3ObjectsPrefix());
    }
}
