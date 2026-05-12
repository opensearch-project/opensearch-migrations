package org.opensearch.migrations;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> solrVersions() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6),
            Arguments.of(SolrClusterContainer.SOLR_7),
            Arguments.of(SolrClusterContainer.SOLR_8),
            Arguments.of(SolrClusterContainer.SOLR_9)
        );
    }

    /** Solr 7 introduced Point-based numeric/date types as the default; Solr 6 used Trie* types. */
    private static String numericIntType(int major) { return major <= 6 ? "tint" : "pint"; }
    private static String dateType(int major)       { return major <= 6 ? "tdate" : "pdate"; }

    /** Solr 6/7 Docker images use /opt/solr/server/solr as SOLR_HOME; 8+ switched to /var/solr/data. */
    private static String solrDataDir(int major)    { return major <= 7 ? "/opt/solr/server/solr" : "/var/solr/data"; }

    /** Solr 9+ renamed managed-schema (no extension) to managed-schema.xml. */
    private static String schemaFileName(int major) { return major >= 9 ? "managed-schema.xml" : "managed-schema"; }

    @ParameterizedTest(name = "Solr {0} → OS")
    @MethodSource("solrVersions")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void solrBackupProducesCorrectOpenSearchMappings(SolrClusterContainer.SolrVersion solrVersion) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(solr::start),
                CompletableFuture.runAsync(target::start)
            ).join();

            var targetOps = new ClusterOperations(target);

            // Create core and add schema fields. Solr 6 lacks Point-based types; use Trie equivalents.
            int major = solrVersion.major();
            exec(solr, "solr", "create_core", "-c", COLLECTION);
            addField(solr, COLLECTION, "title", "string");
            addField(solr, COLLECTION, "count", numericIntType(major));
            addField(solr, COLLECTION, "created", dateType(major));
            addField(solr, COLLECTION, "description", "text_general");
            addField(solr, COLLECTION, "active", "boolean");

            // Index a document to ensure the core has data
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", "[{\"id\":\"1\",\"title\":\"test\",\"count\":42,\"created\":\"2024-01-01T00:00:00Z\",\"description\":\"hello world\",\"active\":true}]"
            );

            // Solr 6: SOLR_HOME=/opt/solr/server/solr (parent already exists, snapshot dir must not pre-exist).
            // Solr 8+: SOLR_HOME=/var/solr/data (Solr creates the snapshot dir automatically).
            var dataDir = solrDataDir(major);

            // Create replication backup
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/replication?command=backup&location=" + dataDir + "&name=meta_bak"
            );
            waitForBackup(solr, COLLECTION, 30);

            // Build backup directory structure expected by ClusterReaderExtractor:
            //   <backupRoot>/<collection>/backup_0.properties  (marker for discoverCollections)
            //   <backupRoot>/<collection>/zk_backup_0/configs/<configName>/managed-schema.xml  (latest zk_backup_N)
            var backupRoot = tempDir.toPath().resolve("solr_backup");
            var collectionDir = backupRoot.resolve(COLLECTION);
            Files.createDirectories(collectionDir);

            // Copy snapshot files so discoverCollections finds the collection
            var snapshotDir = dataDir + "/snapshot.meta_bak";
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
                dataDir + "/" + COLLECTION + "/conf/" + schemaFileName(major),
                configDir.resolve("managed-schema.xml").toString()
            );

            // Run MetadataMigration
            var args = new MigrateOrEvaluateArgs();
            args.fileSystemRepoPath = backupRoot.toString();
            args.sourceVersion = Version.fromString("SOLR " + solrVersion.tag());
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
