package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.testfixtures.AzuriteContainer;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@Tag("isolatedTest")
public class AzureRepoIntegrationTest {

    private static final String CONTAINER_NAME = "test-snapshot-container";

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer();

    private static BlobContainerClient containerClient;

    @BeforeAll
    static void setUp() {
        containerClient = AZURITE.createBlobContainer(CONTAINER_NAME);
    }

    private static void seedBlob(String key, String contents) {
        containerClient.getBlobClient(key)
            .upload(BinaryData.fromBytes(contents.getBytes(StandardCharsets.UTF_8)), true);
    }

    private static AzureRepo createRepo(Path localDir, String basePath) {
        var azureUri = new AzureUri("azure://" + AzuriteContainer.ACCOUNT_NAME + "/" + CONTAINER_NAME + "/" + basePath);
        return AzureRepo.create(
            localDir, azureUri, AZURITE.getEndpoint(), AzuriteContainer.ACCOUNT_KEY, null, new BaseSnapshotFileFinder());
    }

    @Test
    void canReadFileFromAzurite(@TempDir Path tempDir) throws Exception {
        seedBlob("snapshots/index-0", "snapshot repo data content");

        var repo = createRepo(tempDir, "snapshots");

        Path repoRoot = repo.getRepoRootDir();
        assertEquals(tempDir, repoRoot);

        Path dataFile = repo.getSnapshotRepoDataFilePath();
        assertTrue(Files.exists(dataFile));
        assertEquals("snapshot repo data content", Files.readString(dataFile));
    }

    @Test
    void listFilesInRootReturnsOnlyTopLevelEntries(@TempDir Path tempDir) {
        seedBlob("listing/index-0", "a");
        seedBlob("listing/index.latest", "b");
        seedBlob("listing/indices/idx1/meta.dat", "nested");

        var repo = createRepo(tempDir, "listing");

        var names = repo.listFilesInRoot();

        assertTrue(names.contains("index-0"), "expected index-0 in " + names);
        assertTrue(names.contains("index.latest"), "expected index.latest in " + names);
        assertTrue(
            names.stream().noneMatch(n -> n.contains("/")),
            "root listing must not contain nested paths: " + names
        );
    }

    @Test
    void canReadNestedBlob(@TempDir Path tempDir) throws Exception {
        // BaseSnapshotFileFinder names index metadata meta-<indexFileId>.dat.
        seedBlob("nested/indices/idx1/meta-snap1.dat", "nested-contents");

        var repo = createRepo(tempDir, "nested");

        Path metaFile = repo.getIndexMetadataFilePath("idx1", "snap1");
        assertTrue(Files.exists(metaFile));
        assertEquals("nested-contents", Files.readString(metaFile));
    }

    @Test
    void missingBlobThrowsCouldNotReadFromAzureAndLeavesNoLocalFile(@TempDir Path tempDir) {
        var repo = createRepo(tempDir, "absent");

        var thrown = assertThrows(
            AzureRepo.CouldNotReadFromAzure.class,
            () -> repo.getIndexMetadataFilePath("idx1", "snap1")
        );

        assertInstanceOf(BlobStorageException.class, thrown.getCause());
        assertEquals(404, ((BlobStorageException) thrown.getCause()).getStatusCode());
        assertNotNull(SnapshotReadFailures.find(thrown));
        // A leftover partial file would be treated as already downloaded on the next read.
        assertFalse(Files.exists(tempDir.resolve("indices/idx1/meta-snap1.dat")));
    }

    @Test
    void missingRepoRootThrowsCannotFindSnapshotRepoRoot(@TempDir Path tempDir) {
        var repo = createRepo(tempDir, "nothing-here");

        assertThrows(
            AzureRepo.CannotFindSnapshotRepoRoot.class,
            repo::getSnapshotRepoDataFilePath
        );
    }
}
