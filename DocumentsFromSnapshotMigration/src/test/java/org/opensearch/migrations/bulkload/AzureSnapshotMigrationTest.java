package org.opensearch.migrations.bulkload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads a real snapshot from Azure Blob Storage through both migration entry points: the
 * {@code azure://} branch of {@link org.opensearch.migrations.cli.ClusterReaderExtractor} for
 * metadata, and the {@code azure://} branch of {@link RfsMigrateDocuments} for documents.
 *
 * <p>The snapshot is a checked-in filesystem fixture uploaded verbatim to Azurite, so no source
 * cluster with {@code repository-azure} is involved. Only snapshot creation needs that wiring; the
 * read path depends solely on the repository layout, which is the same in every backend.
 *
 * <p>The account key both entry points authenticate with is supplied as an environment variable
 * on the isolatedTest task (see build.gradle). MetadataMigration runs in this JVM and reads it
 * directly; RfsMigrateDocuments runs as a subprocess and inherits it.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class AzureSnapshotMigrationTest extends SourceTestBase {

    // Azurite's well-known development credentials. Fixed emulator constants, published in
    // Microsoft's docs, not secrets. The key matches the isolatedTest environment in build.gradle.
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    private static final String ACCOUNT_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final String CONTAINER_NAME = "snapshot-migration";
    private static final String REPO_PREFIX = "es710";
    private static final int AZURITE_BLOB_PORT = 10000;

    private static final TestResources.Snapshot FIXTURE = TestResources.SNAPSHOT_ES_7_10_W_SOFT;
    private static final String INDEX_NAME = "test_updates_deletes";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Pinned to the same tag as AzureRepoIntegrationTest: azure-storage-blob 12.35.1 sends an API
    // version that older Azurite releases reject.
    @Container
    static final GenericContainer<?> AZURITE =
        new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.37.0")
            .withExposedPorts(AZURITE_BLOB_PORT)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0")
            // Azurite answers unauthenticated HTTP probes with 403, so a status-code wait
            // strategy never goes green. Match the startup log line instead.
            .waitingFor(Wait.forLogMessage(".*successfully listens.*", 1));

    private static String azureEndpoint;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void uploadFixture() throws IOException {
        azureEndpoint = "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(AZURITE_BLOB_PORT);

        BlobContainerClient container = new BlobServiceClientBuilder()
            .endpoint(azureEndpoint + "/" + ACCOUNT_NAME)
            .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
            .buildClient()
            .getBlobContainerClient(CONTAINER_NAME);
        container.createIfNotExists();

        // Upload the fixture verbatim, so blob keys mirror the repository's relative paths.
        try (Stream<Path> files = Files.walk(FIXTURE.dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String key = REPO_PREFIX + "/" + FIXTURE.dir.relativize(file).toString().replace('\\', '/');
                container.getBlobClient(key).upload(BinaryData.fromFile(file), true);
            }
        }
    }

    @Test
    void migratesMetadataAndDocumentsFromAzureSnapshot() throws Exception {
        String repoUri = "azure://" + ACCOUNT_NAME + "/" + CONTAINER_NAME + "/" + REPO_PREFIX;

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            target.start();
            var targetOps = new ClusterOperations(target);

            // 1. Metadata, through ClusterReaderExtractor's azure:// branch.
            var metaArgs = new MigrateOrEvaluateArgs();
            metaArgs.sourceVersion = Version.fromString("ES 7.10");
            metaArgs.snapshotName = FIXTURE.name;
            metaArgs.repoUri = repoUri;
            metaArgs.endpoint = azureEndpoint;
            metaArgs.localDir = Files.createDirectories(tempDir.resolve("metadata-download")).toString();
            metaArgs.targetArgs.host = target.getUrl();

            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var metadataResult = new MetadataMigration().migrate(metaArgs).execute(metadataContext);
            assertEquals(0, metadataResult.getExitCode(), metadataResult.asCliOutput());

            var settings = targetOps.get("/" + INDEX_NAME + "/_settings");
            assertEquals(200, settings.getKey(), "metadata migration should create the index on the target");
            assertEquals("1", MAPPER.readTree(settings.getValue())
                .path(INDEX_NAME).path("settings").path("index").path("number_of_shards").asText(),
                "the fixture index has a single shard");

            // 2. Documents, through RfsMigrateDocuments' azure:// branch. RFS migrates one shard
            // per run, so a single-shard index takes one run plus one that finds no work left.
            String[] rfsArgs = {
                "--snapshot-name", FIXTURE.name,
                "--repo-uri", repoUri,
                "--endpoint", azureEndpoint,
                "--local-dir", Files.createDirectories(tempDir.resolve("rfs-download")).toString(),
                "--lucene-dir", Files.createDirectories(tempDir.resolve("lucene")).toString(),
                "--target-host", target.getUrl(),
                "--index-allowlist", INDEX_NAME,
                "--source-version", "ES_7_10"
            };
            int exitCode = 0;
            for (int run = 0; run < 3 && exitCode != RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE; run++) {
                exitCode = waitForProcessExit(runAndMonitorProcess(setupProcess(rfsArgs)), 300);
                log.atInfo().setMessage("RfsMigrateDocuments run {} exited with {}")
                    .addArgument(run).addArgument(exitCode).log();
                if (exitCode != RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE) {
                    assertEquals(0, exitCode, "each document migration run should succeed");
                }
            }
            assertEquals(RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE, exitCode, "all shards should be migrated");

            // The fixture holds three live documents; the deleted and superseded versions in its
            // history must not reach the target.
            targetOps.get("/" + INDEX_NAME + "/_refresh");
            var count = targetOps.get("/" + INDEX_NAME + "/_count");
            assertEquals(200, count.getKey());
            assertEquals(3, MAPPER.readTree(count.getValue()).path("count").asInt());

            for (var expected : List.of(
                List.of("complexdoc", "This is a doc with complex history", "Updated!"),
                List.of("updateddoc", "This is doc that will be updated", "Updated!"),
                List.of("unchangeddoc",
                    "This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines.",
                    "bluh bluh")
            )) {
                var doc = targetOps.get("/" + INDEX_NAME + "/_doc/" + expected.get(0));
                assertEquals(200, doc.getKey(), "document " + expected.get(0) + " should be on the target");
                var source = MAPPER.readTree(doc.getValue()).path("_source");
                assertEquals(expected.get(1), source.path("title").asText());
                assertEquals(expected.get(2), source.path("content").asText());
            }
        }
    }
}
