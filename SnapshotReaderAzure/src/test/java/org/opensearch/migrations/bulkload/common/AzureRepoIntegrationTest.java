package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

    // Azurite's well-known development credentials. Fixed emulator constants, published in
    // Microsoft's docs, not secrets.
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    private static final String ACCOUNT_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final String CONTAINER_NAME = "test-snapshot-container";
    private static final int AZURITE_BLOB_PORT = 10000;

    // Pinned: azure-storage-blob 12.35.1 sends API version 2026-06-06, which Azurite 3.35.0
    // rejects with InvalidHeaderValue. Testcontainers pins no tag of its own.
    @Container
    static final GenericContainer<?> AZURITE =
        new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.37.0")
            .withExposedPorts(AZURITE_BLOB_PORT)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0")
            // Azurite answers unauthenticated HTTP probes with 403, so a status-code wait
            // strategy never goes green. Match the startup log line instead.
            .waitingFor(Wait.forLogMessage(".*successfully listens.*", 1));

    private static String azureEndpoint;
    private static BlobContainerClient containerClient;

    @BeforeAll
    static void setUp() {
        // Azurite uses path-style addressing, with the account as the first path segment.
        azureEndpoint = "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(AZURITE_BLOB_PORT);

        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
            .endpoint(azureEndpoint + "/" + ACCOUNT_NAME)
            .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
            .buildClient();

        containerClient = serviceClient.getBlobContainerClient(CONTAINER_NAME);
        containerClient.createIfNotExists();
    }

    private static void seedBlob(String key, String contents) {
        containerClient.getBlobClient(key)
            .upload(BinaryData.fromBytes(contents.getBytes(StandardCharsets.UTF_8)), true);
    }

    private static AzureRepo createRepo(Path localDir, String basePath) {
        var azureUri = new AzureUri("azure://" + ACCOUNT_NAME + "/" + CONTAINER_NAME + "/" + basePath);
        return AzureRepo.create(localDir, azureUri, azureEndpoint, new BaseSnapshotFileFinder());
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

        // Only top-level entries are part of the repo root listing; the nested blob must not
        // surface there. Which layer excludes it, the hierarchy listing or the reader's own
        // filter, is covered by AzureRepoTest.
        assertTrue(names.contains("index-0"), "expected index-0 in " + names);
        assertTrue(names.contains("index.latest"), "expected index.latest in " + names);
        assertTrue(
            names.stream().noneMatch(n -> n.contains("/")),
            "root listing must not contain nested paths: " + names
        );
    }

    @Test
    void canReadNestedBlob(@TempDir Path tempDir) throws Exception {
        // BaseSnapshotFileFinder builds the index metadata name as meta-<indexFileId>.dat, so the
        // seeded key has to match that shape rather than being a bare file name.
        seedBlob("nested/indices/idx1/meta-snap1.dat", "nested-contents");

        var repo = createRepo(tempDir, "nested");

        Path metaFile = repo.getIndexMetadataFilePath("idx1", "snap1");
        assertTrue(Files.exists(metaFile));
        assertEquals("nested-contents", Files.readString(metaFile));
    }

    @Test
    void missingBlobThrowsCouldNotReadFromAzureAndLeavesNoLocalFile(@TempDir Path tempDir) {
        var repo = createRepo(tempDir, "absent");

        // The reader downloads without a separate existence check, so the service's 404 must be
        // what gets classified. A partial file left behind would be mistaken for a completed
        // download on the next read, since files already present locally are not fetched again.
        var thrown = assertThrows(
            AzureRepo.CouldNotReadFromAzure.class,
            () -> repo.getIndexMetadataFilePath("idx1", "snap1")
        );

        assertInstanceOf(BlobStorageException.class, thrown.getCause());
        assertEquals(404, ((BlobStorageException) thrown.getCause()).getStatusCode());
        assertNotNull(SnapshotReadFailures.find(thrown));
        assertFalse(Files.exists(tempDir.resolve("indices/idx1/meta-snap1.dat")));
    }

    @Test
    void missingRepoRootThrowsCannotFindSnapshotRepoRoot(@TempDir Path tempDir) {
        var repo = createRepo(tempDir, "nothing-here");

        // An empty prefix listing returns zero items and raises no exception of its own, so the
        // reader has to detect the empty result and throw.
        assertThrows(
            AzureRepo.CannotFindSnapshotRepoRoot.class,
            repo::getSnapshotRepoDataFilePath
        );
    }
}
