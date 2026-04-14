package org.opensearch.migrations;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * E2E test: Solr backup → MetadataMigration → OpenSearch.
 * Verifies that the ClusterReaderExtractor Solr snapshot path produces correct OpenSearch mappings.
 */
@Tag("isolatedTest")
@Slf4j
class SolrMetadataMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "test_fields";

    @TempDir
    File tempDir;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void solrBackupProducesCorrectOpenSearchMappings() throws Exception {
        try (
            var solr = new SolrClusterContainer(SolrClusterContainer.SOLR_8);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(solr::start),
                CompletableFuture.runAsync(target::start)
            ).join();

            var targetOps = new ClusterOperations(target);

            // Create core and add schema fields
            exec(solr, "solr", "create_core", "-c", COLLECTION);
            addField(solr, COLLECTION, "title", "string");
            addField(solr, COLLECTION, "count", "pint");
            addField(solr, COLLECTION, "created", "pdate");
            addField(solr, COLLECTION, "description", "text_general");
            addField(solr, COLLECTION, "active", "boolean");

            // Index a document to ensure the core has data
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", "[{\"id\":\"1\",\"title\":\"test\",\"count\":42,\"created\":\"2024-01-01T00:00:00Z\",\"description\":\"hello world\",\"active\":true}]"
            );

            // Create replication backup
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/replication?command=backup&location=/var/solr/data&name=meta_bak"
            );
            waitForBackup(solr, COLLECTION, 30);

            // Build backup directory structure expected by ClusterReaderExtractor:
            //   <backupRoot>/<collection>/backup_0.properties  (marker for discoverCollections)
            //   <backupRoot>/<collection>/zk_backup_0/configs/<configName>/managed-schema.xml
            var backupRoot = tempDir.toPath().resolve("solr_backup");
            var collectionDir = backupRoot.resolve(COLLECTION);
            Files.createDirectories(collectionDir);

            // Copy snapshot files so discoverCollections finds the collection
            var snapshotDir = "/var/solr/data/snapshot.meta_bak";
            var listResult = solr.execInContainer("ls", snapshotDir);
            for (var fileName : listResult.getStdout().trim().split("\n")) {
                if (fileName.isEmpty()) continue;
                solr.copyFileFromContainer(
                    snapshotDir + "/" + fileName,
                    collectionDir.resolve(fileName).toString()
                );
            }

            // Copy managed-schema into ZK backup structure for SolrSchemaXmlParser
            var configDir = collectionDir.resolve("zk_backup_0").resolve("configs").resolve("_default");
            Files.createDirectories(configDir);
            solr.copyFileFromContainer(
                "/var/solr/data/" + COLLECTION + "/conf/managed-schema",
                configDir.resolve("managed-schema.xml").toString()
            );

            // Run MetadataMigration
            var args = new MigrateOrEvaluateArgs();
            args.fileSystemRepoPath = backupRoot.toString();
            args.sourceVersion = Version.fromString("SOLR 8.11.4");
            args.targetArgs.host = target.getUrl();

            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var result = new MetadataMigration().migrate(args).execute(metadataContext);

            log.atInfo().setMessage("Migration result: {}").addArgument(result.asCliOutput()).log();
            assertThat("Migration should succeed", result.getExitCode(), equalTo(0));

            // Verify OpenSearch index was created with correct mappings
            var res = targetOps.get("/" + COLLECTION + "/_mapping");
            assertThat(res.getKey(), equalTo(200));

            var mappings = MAPPER.readTree(res.getValue());
            var properties = mappings.path(COLLECTION).path("mappings").path("properties");

            assertThat("title should map to keyword", properties.path("title").path("type").asText(), equalTo("keyword"));
            assertThat("count should map to integer", properties.path("count").path("type").asText(), equalTo("integer"));
            assertThat("created should map to date", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("description should map to text", properties.path("description").path("type").asText(), equalTo("text"));
            assertThat("active should map to boolean", properties.path("active").path("type").asText(), equalTo("boolean"));
        }
    }

    private static void addField(SolrClusterContainer solr, String collection, String name, String type) throws Exception {
        var result = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-H", "Content-Type: application/json",
            "-d", "{\"add-field\":{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"stored\":true}}"
        );
        log.atInfo().setMessage("Add field {} ({}): {}").addArgument(name).addArgument(type).addArgument(result.getStdout()).log();
    }

    private static void exec(SolrClusterContainer solr, String... cmd) throws Exception {
        var result = solr.execInContainer(cmd);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\n" + result.getStderr());
        }
    }

    private static void waitForBackup(SolrClusterContainer solr, String collection, int maxWaitSeconds) throws Exception {
        for (int i = 0; i < maxWaitSeconds; i++) {
            var result = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + collection + "/replication?command=details&wt=json");
            if (result.getStdout().contains("\"status\":\"success\"") || result.getStdout().contains("success")) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Backup did not complete within " + maxWaitSeconds + "s");
    }
}
