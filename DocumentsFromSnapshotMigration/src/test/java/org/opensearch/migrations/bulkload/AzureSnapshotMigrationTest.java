package org.opensearch.migrations.bulkload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.testfixtures.AzuriteContainer;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Migrates snapshots held in Azurite through the azure:// branches of MetadataMigration and
 * RfsMigrateDocuments, including one that Elasticsearch itself wrote to Azurite.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class AzureSnapshotMigrationTest extends SourceTestBase {

    private static final String CONTAINER_NAME = "snapshot-migration";
    private static final String AZURITE_ALIAS = "azurite";

    private static final TestResources.Snapshot FIXTURE = TestResources.SNAPSHOT_ES_7_10_W_SOFT;
    private static final String FIXTURE_PREFIX = "es710";
    private static final String FIXTURE_INDEX = "test_updates_deletes";

    private static final String WRITTEN_PREFIX = "es710-written";
    private static final String WRITTEN_SNAPSHOT = "written_snapshot";
    private static final String WRITTEN_INDEX = "written_index";
    private static final int WRITTEN_DOC_COUNT = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer()
        .withNetwork(NETWORK)
        .withNetworkAliases(AZURITE_ALIAS);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void uploadFixture() throws IOException {
        BlobContainerClient container = AZURITE.createBlobContainer(CONTAINER_NAME);
        try (Stream<Path> files = Files.walk(FIXTURE.dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String key = FIXTURE_PREFIX + "/" + FIXTURE.dir.relativize(file).toString().replace('\\', '/');
                container.getBlobClient(key).upload(BinaryData.fromFile(file), true);
            }
        }
    }

    @Test
    void migratesMetadataAndDocumentsFromAzureSnapshot() throws Exception {
        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            target.start();
            var targetOps = new ClusterOperations(target);

            migrateFromAzure(target, repoUri(FIXTURE_PREFIX), FIXTURE.name, FIXTURE_INDEX);

            var settings = targetOps.get("/" + FIXTURE_INDEX + "/_settings");
            assertEquals(200, settings.getKey(), "metadata migration should create the index on the target");
            assertEquals("1", MAPPER.readTree(settings.getValue())
                .path(FIXTURE_INDEX).path("settings").path("index").path("number_of_shards").asText(),
                "the fixture index has a single shard");
            assertEquals(3, countDocuments(targetOps, FIXTURE_INDEX));

            for (var expected : List.of(
                List.of("complexdoc", "This is a doc with complex history", "Updated!"),
                List.of("updateddoc", "This is doc that will be updated", "Updated!"),
                List.of("unchangeddoc",
                    "This doc will not be changed\nIt has multiple lines of text\nIts source doc has extra newlines.",
                    "bluh bluh")
            )) {
                var doc = targetOps.get("/" + FIXTURE_INDEX + "/_doc/" + expected.get(0));
                assertEquals(200, doc.getKey(), "document " + expected.get(0) + " should be on the target");
                var source = MAPPER.readTree(doc.getValue()).path("_source");
                assertEquals(expected.get(1), source.path("title").asText());
                assertEquals(expected.get(2), source.path("content").asText());
            }
        }
    }

    @Test
    void migratesSnapshotThatElasticsearchWroteToAzurite() throws Exception {
        try (
            var source = elasticsearchWritingToAzurite();
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(source::start),
                CompletableFuture.runAsync(target::start)
            ).join();

            var sourceOps = new ClusterOperations(source);
            sourceOps.createIndex(WRITTEN_INDEX,
                "{\"settings\": {\"number_of_shards\": 1, \"number_of_replicas\": 0}}");
            for (int i = 0; i < WRITTEN_DOC_COUNT; i++) {
                sourceOps.createDocument(WRITTEN_INDEX, "doc" + i, "{\"n\": " + i + "}");
            }
            sourceOps.post("/" + WRITTEN_INDEX + "/_refresh", null);

            // verify=false: ES 7.10 derives blob names from host-style URLs, so its cleanup misnames Azurite blobs.
            var registration = sourceOps.put("/_snapshot/azure_repo?verify=false",
                "{\"type\": \"azure\", \"settings\": {\"container\": \"" + CONTAINER_NAME
                    + "\", \"base_path\": \"" + WRITTEN_PREFIX + "\", \"compress\": true}}");
            assertEquals(200, registration.getKey(), registration.getValue());
            sourceOps.takeSnapshot("azure_repo", WRITTEN_SNAPSHOT, WRITTEN_INDEX);

            migrateFromAzure(target, repoUri(WRITTEN_PREFIX), WRITTEN_SNAPSHOT, WRITTEN_INDEX);

            var targetOps = new ClusterOperations(target);
            assertEquals(WRITTEN_DOC_COUNT, countDocuments(targetOps, WRITTEN_INDEX));
            var doc = targetOps.get("/" + WRITTEN_INDEX + "/_doc/doc7");
            assertEquals(200, doc.getKey());
            assertEquals(7, MAPPER.readTree(doc.getValue()).path("_source").path("n").asInt());
        }
    }

    private static SearchClusterContainer elasticsearchWritingToAzurite() {
        var container = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
        container.withNetwork(NETWORK)
            .withEnv("AZURE_ACCOUNT", AzuriteContainer.ACCOUNT_NAME)
            .withEnv("AZURE_KEY", AzuriteContainer.ACCOUNT_KEY)
            // repository-azure has no endpoint setting, but appends endpoint_suffix to its connection string.
            .withEnv("AZURE_ENDPOINT_SUFFIX", "ignored;DefaultEndpointsProtocol=http;BlobEndpoint=http://"
                + AZURITE_ALIAS + ":10000/" + AzuriteContainer.ACCOUNT_NAME)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c",
                "{ [ -f config/elasticsearch.keystore ] || bin/elasticsearch-keystore create; }"
                    + " && printf %s \"$AZURE_ACCOUNT\" | bin/elasticsearch-keystore add --stdin --force azure.client.default.account"
                    + " && printf %s \"$AZURE_KEY\" | bin/elasticsearch-keystore add --stdin --force azure.client.default.key"
                    + " && exec /usr/local/bin/entrypoint.sh -Eazure.client.default.endpoint_suffix=\"$AZURE_ENDPOINT_SUFFIX\""));
        return container;
    }

    private static String repoUri(String prefix) {
        return "azure://" + AzuriteContainer.ACCOUNT_NAME + "/" + CONTAINER_NAME + "/" + prefix;
    }

    private void migrateFromAzure(SearchClusterContainer target, String repoUri, String snapshotName, String index)
        throws IOException {
        var metaArgs = new MigrateOrEvaluateArgs();
        metaArgs.sourceVersion = Version.fromString("ES 7.10");
        metaArgs.snapshotName = snapshotName;
        metaArgs.repoUri = repoUri;
        metaArgs.endpoint = AZURITE.getEndpoint();
        metaArgs.azureStorageAccountKey = AzuriteContainer.ACCOUNT_KEY;
        metaArgs.localDir = Files.createTempDirectory(tempDir, "metadata-download").toString();
        metaArgs.targetArgs.host = target.getUrl();

        var metadataResult = new MetadataMigration().migrate(metaArgs)
            .execute(MetadataMigrationTestContext.factory().noOtelTracking());
        assertEquals(0, metadataResult.getExitCode(), metadataResult.asCliOutput());

        String[] rfsArgs = {
            "--snapshot-name", snapshotName,
            "--repo-uri", repoUri,
            "--endpoint", AZURITE.getEndpoint(),
            "--azure-storage-account-key", AzuriteContainer.ACCOUNT_KEY,
            "--local-dir", Files.createTempDirectory(tempDir, "rfs-download").toString(),
            "--lucene-dir", Files.createTempDirectory(tempDir, "lucene").toString(),
            "--target-host", target.getUrl(),
            "--index-allowlist", index,
            "--source-version", "ES_7_10"
        };
        // RFS migrates one shard per run, then exits with NO_WORK_LEFT on the next.
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
    }

    private static int countDocuments(ClusterOperations ops, String index) throws IOException {
        ops.get("/" + index + "/_refresh");
        var count = ops.get("/" + index + "/_count");
        assertEquals(200, count.getKey());
        return MAPPER.readTree(count.getValue()).path("count").asInt();
    }
}
