package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.bulkload.version_es_6_8.SnapshotFileFinder_ES_6_8;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.matchers.JUnitMatchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class S3RepoTest {
    @Mock
    private S3AsyncClient mockS3Client;

    private S3Repo testRepo;
    private Path testDir;
    private final String testRegion = "us-fake-1";
    private final S3Uri testRepoUri = new S3Uri("s3://bucket-name/test-repo");

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        this.testDir = tempDir;

        // Create fake files as expected by SnapshotFileFinder_ES_6_8
        Files.createFile(tempDir.resolve("index-2"));
        Files.createFile(tempDir.resolve("snap-snapshot1.dat"));
        Files.createFile(tempDir.resolve("meta-snapshot1.dat"));
        Files.createDirectories(tempDir.resolve("indices/123abc"));
        Files.createFile(tempDir.resolve("indices/123abc/meta-234bcd.dat"));
        Files.createDirectories(tempDir.resolve("indices/123abc/7"));
        Files.createFile(tempDir.resolve("indices/123abc/7/snap-snapshot1.dat"));
        Files.createFile(tempDir.resolve("indices/123abc/7/bobloblaw"));

        SnapshotFileFinder finder = new SnapshotFileFinder_ES_6_8();

        GetObjectResponse mockResponse = GetObjectResponse.builder().build();
        CompletableFuture<GetObjectResponse> noopFuture = CompletableFuture.completedFuture(mockResponse);
        when(mockS3Client.getObject(any(GetObjectRequest.class), any(AsyncResponseTransformer.class)))
                .thenReturn(noopFuture);

        testRepo = new S3Repo(tempDir, testRepoUri, testRegion, mockS3Client, finder);
    }

    @Test
    void GetRepoRootDir_AsExpected() throws IOException {
        assertEquals(testDir, testRepo.getRepoRootDir());
    }

    @Test
    void makeS3Uri_shouldThrowIfPathOutsideS3LocalDir() {
        Path unrelatedPath = Paths.get("/not/inside/tempDir/index-2");

        var finder = mock(SnapshotFileFinder.class);
        when(finder.getSnapshotRepoDataFilePath()).thenReturn(unrelatedPath);

        var testRepo = new S3Repo(testDir, testRepoUri, testRegion, mockS3Client, finder);

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> testRepo.getSnapshotRepoDataFilePath());

        assertThat(thrown.getMessage(), containsString("File path must be under s3LocalDir"));
    }

    @Test
    void GetSnapshotRepoDataFilePath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("index-2");
        Path actualPath = testRepo.getSnapshotRepoDataFilePath();
        assertEquals(expectedPath, actualPath);
    }

    @Test
    void GetGlobalMetadataFilePath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("meta-snapshot1.dat");
        Path actualPath = testRepo.getGlobalMetadataFilePath("snapshot1");
        assertEquals(expectedPath, actualPath);
    }

    @Test
    void GetSnapshotMetadataFilePath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("snap-snapshot1.dat");
        Path actualPath = testRepo.getSnapshotMetadataFilePath("snapshot1");
        assertEquals(expectedPath, actualPath);
    }

    @Test
    void GetIndexMetadataFilePath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("indices/123abc/meta-234bcd.dat");
        Path actualPath = testRepo.getIndexMetadataFilePath("123abc", "234bcd");
        assertEquals(expectedPath, actualPath);
    }

    @Test
    void GetShardDirPath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("indices/123abc/7");
        Path actualPath = testRepo.getShardDirPath("123abc", 7);
        assertEquals(expectedPath, actualPath);
    }

    @Test
    void GetShardMetadataFilePath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("indices/123abc/7/snap-snapshot1.dat");
        Path actualPath = testRepo.getShardMetadataFilePath("snapshot1", "123abc", 7);
        assertEquals(expectedPath, actualPath);
    }

    @Test
    void GetBlobFilePath_AsExpected() throws IOException {
        Path expectedPath = testDir.resolve("indices/123abc/7/bobloblaw");
        Path actualPath = testRepo.getBlobFilePath("123abc", 7, "bobloblaw");
        assertEquals(expectedPath, actualPath);
    }
}
