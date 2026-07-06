package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@Tag("isolatedTest")
public class GcsRepoIntegrationTest {

    private static final String BUCKET_NAME = "test-snapshot-bucket";
    private static final int FAKE_GCS_PORT = 4443;

    @Container
    static final GenericContainer<?> FAKE_GCS = new GenericContainer<>("fsouza/fake-gcs-server:latest")
        .withExposedPorts(FAKE_GCS_PORT)
        .withCommand("-scheme", "http", "-port", String.valueOf(FAKE_GCS_PORT))
        .waitingFor(Wait.forHttp("/storage/v1/b")
            .forPort(FAKE_GCS_PORT)
            .forStatusCode(200));

    private static String gcsEndpoint;
    private static Storage storageClient;

    @BeforeAll
    static void setUp() {
        gcsEndpoint = "http://" + FAKE_GCS.getHost() + ":" + FAKE_GCS.getMappedPort(FAKE_GCS_PORT);
        storageClient = StorageOptions.newBuilder()
            .setHost(gcsEndpoint)
            .setProjectId("test-project")
            .build()
            .getService();
        storageClient.create(com.google.cloud.storage.BucketInfo.of(BUCKET_NAME));
    }

    @Test
    void canReadFileFromFakeGcs(@TempDir Path tempDir) {
        String content = "snapshot repo data content";
        String key = "snapshots/index-0";
        storageClient.create(
            BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, key)).build(),
            content.getBytes(StandardCharsets.UTF_8)
        );

        var gcsUri = new GcsUri("gs://" + BUCKET_NAME + "/snapshots");
        var repo = GcsRepo.create(tempDir, gcsUri, gcsEndpoint, new BaseSnapshotFileFinder());

        Path repoRoot = repo.getRepoRootDir();
        assertEquals(tempDir, repoRoot);

        Path dataFile = repo.getSnapshotRepoDataFilePath();
        assertTrue(Files.exists(dataFile));
    }

    @Test
    void createWithEndpointConnectsToFakeServer(@TempDir Path tempDir) {
        String key = "repo/index-0";
        storageClient.create(
            BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, key)).build(),
            "test".getBytes(StandardCharsets.UTF_8)
        );

        var gcsUri = new GcsUri("gs://" + BUCKET_NAME + "/repo");
        var repo = GcsRepo.create(tempDir, gcsUri, gcsEndpoint, new BaseSnapshotFileFinder());

        Path dataFile = repo.getSnapshotRepoDataFilePath();
        assertTrue(Files.exists(dataFile));
    }
}
