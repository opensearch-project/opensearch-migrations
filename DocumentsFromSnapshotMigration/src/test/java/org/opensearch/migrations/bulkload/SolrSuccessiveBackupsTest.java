package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that successive Solr backups to the same location are handled correctly:
 * only data from the latest backup revision is migrated, and data that existed
 * only in an earlier backup (deleted before the second backup) is NOT migrated.
 *
 * <p>Mirrors the pattern from {@code DeltaSnapshotRestoreTest} for Elasticsearch.
 *
 * <p>Solr's Collections API BACKUP to the same name creates successive revisions:
 * {@code zk_backup_0}, {@code zk_backup_1}, etc. and {@code md_shard1_0.json},
 * {@code md_shard1_1.json}. The migration code must pick the latest (highest N).
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class SolrSuccessiveBackupsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "movies";

    @TempDir
    File tempDir;

    static Stream<Arguments> solr8ToOpenSearch3() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V3_5_0)
        );
    }

    /**
     * Successive cloud backups: index docs A, backup, modify, backup again.
     * Migration should only see data from the second (latest) backup.
     */
    @ParameterizedTest(name = "successive cloud backups: {0} → {1}")
    @MethodSource("solr8ToOpenSearch3")
    void successiveBackupsMigratesOnlyLatest(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = SolrClusterContainer.cloud(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            // Create collection
            createCollection(solr, COLLECTION);

            // === Phase 1: Index initial docs and take backup #1 ===
            log.info("Phase 1: indexing initial documents");
            indexDocs(solr, COLLECTION, new String[][]{
                {"old-1", "Old Movie One"},
                {"old-2", "Old Movie Two"},
                {"old-3", "Old Movie Three"},
                {"shared-1", "Shared Movie"},
                {"shared-2", "Another Shared"},
            });

            var backupLocation = "/var/solr/data/backups";
            solr.execInContainer("mkdir", "-p", backupLocation);

            log.info("Phase 1: creating backup #1");
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=BACKUP"
                    + "&name=migration_backup&collection=" + COLLECTION
                    + "&location=" + backupLocation + "&wt=json");
            Thread.sleep(2000);

            // === Phase 2: Modify data, take backup #2 ===
            log.info("Phase 2: deleting old-only docs, adding new docs");
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", "{\"delete\":{\"query\":\"id:old-1 OR id:old-2 OR id:old-3\"}}");

            indexDocs(solr, COLLECTION, new String[][]{
                {"new-1", "New Movie Alpha"},
                {"new-2", "New Movie Beta"},
                {"new-3", "New Movie Gamma"},
                {"new-4", "New Movie Delta"},
            });

            log.info("Phase 2: creating backup #2 (same name, adds zk_backup_1)");
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=BACKUP"
                    + "&name=migration_backup&collection=" + COLLECTION
                    + "&location=" + backupLocation + "&wt=json");
            Thread.sleep(2000);

            // Verify the backup has zk_backup_0 AND zk_backup_1
            var containerBackupDir = backupLocation + "/migration_backup/" + COLLECTION;
            var dirList = solr.execInContainer("find", containerBackupDir,
                "-maxdepth", "1", "-type", "d", "-name", "zk_backup_*");
            log.info("ZK backup dirs found: {}", dirList.getStdout().trim());
            assertThat("Should have multiple zk_backup dirs",
                dirList.getStdout().trim().split("\n").length, greaterThan(1));

            // === Copy backup to local filesystem ===
            var backupRoot = tempDir.toPath().resolve("solr_backup");
            var collectionDir = backupRoot.resolve(COLLECTION);
            copyDirectoryFromContainer(solr, containerBackupDir, collectionDir);

            // === Run migration via CLI (same approach as SolrSnapshotToOpenSearchTest) ===
            log.info("Running migration against backup with successive revisions");
            int exitCode = SourceTestBase.runProcessAgainstTarget(new String[]{
                "--source-version", "SOLR_8.11.4",
                "--snapshot-local-dir", backupRoot.toString(),
                "--snapshot-name", "solr-successive",
                "--target-host", target.getUrl(),
                "--coordinator-host", target.getUrl(),
                "--index-allowlist", COLLECTION,
            });
            assertEquals(0, exitCode, "RfsMigrateDocuments should exit successfully");

            // === Verify results ===
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            var restClient = new RestClient(
                ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext()
            );
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // Expected: shared-1, shared-2 (survived both backups) + new-1..new-4 = 6 docs
            verifyDocCount(target, COLLECTION, 6);

            // Verify old-only docs are NOT present
            for (String oldId : List.of("old-1", "old-2", "old-3")) {
                var resp = restClient.get(
                    COLLECTION + "/_doc/" + oldId,
                    ctx.createUnboundRequestContext()
                );
                var found = MAPPER.readTree(resp.body).path("found").asBoolean(true);
                assertEquals(false, found,
                    "Doc '" + oldId + "' was only in backup #1 and should NOT be migrated");
            }

            // Verify new docs ARE present
            for (String newId : List.of("new-1", "new-2", "new-3", "new-4", "shared-1", "shared-2")) {
                var resp = restClient.get(
                    COLLECTION + "/_doc/" + newId,
                    ctx.createUnboundRequestContext()
                );
                var found = MAPPER.readTree(resp.body).path("found").asBoolean(false);
                assertEquals(true, found,
                    "Doc '" + newId + "' should be present from the latest backup");
            }

            log.info("SUCCESS: Only latest backup data was migrated, old-only data excluded");
        }
    }

    // --- Helpers ---

    private static void createCollection(SolrClusterContainer solr, String collection) throws Exception {
        solr.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + collection + "&numShards=1&replicationFactor=1"
                + "&maxShardsPerNode=2&wt=json");
    }

    private static void indexDocs(SolrClusterContainer solr, String collection, String[][] docs) throws Exception {
        for (String[] doc : docs) {
            var json = String.format("[{\"id\":\"%s\",\"title\":\"%s\"}]", doc[0], doc[1]);
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + collection + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", json);
        }
    }

    private static void copyDirectoryFromContainer(
        SolrClusterContainer solr, String containerDir, Path localDir
    ) throws Exception {
        Files.createDirectories(localDir);
        var findResult = solr.execInContainer("find", containerDir, "-type", "f");
        for (var line : findResult.getStdout().trim().split("\n")) {
            if (line.isEmpty()) continue;
            var relativePath = line.substring(containerDir.length());
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            var localFile = localDir.resolve(relativePath);
            Files.createDirectories(localFile.getParent());
            solr.copyFileFromContainer(line, localFile.toString());
        }
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = new RestClient(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        );
        restClient.get("_refresh", context.createUnboundRequestContext());
        assertEquals(
            expected,
            new SearchClusterRequests(context)
                .getMapOfIndexAndDocCount(restClient)
                .getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName
        );
    }
}
