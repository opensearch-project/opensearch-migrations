package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Migrating one of several backups of the same collection.
 *
 * <p>Three backup operations, three methods — they are not parameterizations of one scenario:
 * <ul>
 *   <li><b>Solr 8/9 incremental</b> — repeating BACKUP under the same name adds a revision
 *       ({@code zk_backup_0}, {@code zk_backup_1}, …) and the reader must take the highest N.</li>
 *   <li><b>Solr 6/7 named</b> — non-incremental, so each backup is a separately named full copy
 *       writing a bare {@code zk_backup/} with no numeric suffix. Selection is by name.</li>
 *   <li><b>Standalone</b> — the replication handler writes {@code snapshot.&lt;name&gt;/} rather than
 *       going through the Collections API at all.</li>
 * </ul>
 *
 * <p>The named cases migrate a deliberately <em>older</em> backup, since picking the wrong one
 * yields stale data that otherwise looks like a clean success.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 20, unit = TimeUnit.MINUTES)
public class SolrSuccessiveBackupsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "movies";

    /** Present in every backup. */
    private static final String[][] SHARED_DOCS = {
        {"shared-1", "Shared Movie"},
        {"shared-2", "Another Shared"},
    };
    /** Only in the first backup — deleted before the second. */
    private static final String[][] OLD_DOCS = {
        {"old-1", "Old Movie One"},
        {"old-2", "Old Movie Two"},
        {"old-3", "Old Movie Three"},
    };
    /** Only in the second backup. */
    private static final String[][] NEW_DOCS = {
        {"new-1", "New Movie Alpha"},
        {"new-2", "New Movie Beta"},
        {"new-3", "New Movie Gamma"},
        {"new-4", "New Movie Delta"},
    };

    @TempDir
    File tempDir;

    static Stream<Arguments> incrementalVersions() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SolrClusterContainer.SOLR_9, SearchClusterContainer.OS_V3_5_0)
        );
    }

    static Stream<Arguments> nonIncrementalVersions() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6, SearchClusterContainer.OS_V3_5_0),
            Arguments.of(SolrClusterContainer.SOLR_7, SearchClusterContainer.OS_V3_5_0)
        );
    }

    static Stream<Arguments> standaloneVersions() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V3_5_0)
        );
    }

    /**
     * Solr 8/9: two BACKUPs under the same name produce numbered revisions; the reader takes the
     * latest, so docs deleted before the second backup must not arrive.
     */
    @ParameterizedTest(name = "incremental cloud: {0} → {1}")
    @MethodSource("incrementalVersions")
    void incrementalCloudBackupMigratesLatestRevision(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = SolrClusterContainer.cloud(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();
            createCloudCollection(solr, solrVersion);

            var backupLocation = solrDataDir(solr) + "/backups";
            solr.execInContainer("mkdir", "-p", backupLocation);

            indexDocs(solr, COLLECTION, OLD_DOCS);
            indexDocs(solr, COLLECTION, SHARED_DOCS);
            cloudBackup(solr, "migration_backup", backupLocation);

            deleteOldDocs(solr);
            indexDocs(solr, COLLECTION, NEW_DOCS);
            cloudBackup(solr, "migration_backup", backupLocation);

            // Same name twice must have produced more than one revision.
            var containerBackupDir = backupLocation + "/migration_backup/" + COLLECTION;
            var dirList = solr.execInContainer("find", containerBackupDir,
                "-maxdepth", "1", "-type", "d", "-name", "zk_backup_*");
            log.info("ZK backup dirs found: {}", dirList.getStdout().trim());
            assertThat("Repeating BACKUP under one name should add a revision",
                dirList.getStdout().trim().split("\n").length, greaterThan(1));

            var backupRoot = tempDir.toPath().resolve("incremental_" + solrVersion.major());
            copyDirectoryFromContainer(solr, containerBackupDir, backupRoot.resolve(COLLECTION));

            migrate(backupRoot, solrVersion, target);

            verifyDocCount(target, COLLECTION, SHARED_DOCS.length + NEW_DOCS.length);
            assertDocsAbsent(target, OLD_DOCS);
            assertDocsPresent(target, SHARED_DOCS);
            assertDocsPresent(target, NEW_DOCS);
        }
    }

    /**
     * Solr 6/7: BACKUP is not incremental, so each run needs its own name. Migrating the first
     * backup by name must yield its contents, not whatever the newest backup holds.
     */
    @ParameterizedTest(name = "named cloud: {0} → {1}")
    @MethodSource("nonIncrementalVersions")
    void namedCloudBackupMigratesSelectedBackup(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = SolrClusterContainer.cloud(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();
            createCloudCollection(solr, solrVersion);

            var backupLocation = solrDataDir(solr) + "/backups";
            solr.execInContainer("mkdir", "-p", backupLocation);

            indexDocs(solr, COLLECTION, OLD_DOCS);
            indexDocs(solr, COLLECTION, SHARED_DOCS);
            cloudBackup(solr, "backup_v1", backupLocation);

            deleteOldDocs(solr);
            indexDocs(solr, COLLECTION, NEW_DOCS);
            cloudBackup(solr, "backup_v2", backupLocation);

            // Solr 6/7 write a bare zk_backup/ with no numeric suffix — proves this is the
            // non-incremental layout rather than a revision of one backup.
            var bareZk = solr.execInContainer("find", backupLocation + "/backup_v1",
                "-maxdepth", "2", "-type", "d", "-name", "zk_backup");
            assertThat("Solr " + solrVersion.major() + " should write a bare zk_backup/ directory",
                bareZk.getStdout().trim().isEmpty(), org.hamcrest.Matchers.is(false));

            // Deliberately migrate the OLDER backup.
            var backupRoot = tempDir.toPath().resolve("named_cloud_" + solrVersion.major());
            copyDirectoryFromContainer(solr, backupLocation + "/backup_v1", backupRoot.resolve(COLLECTION));

            migrate(backupRoot, solrVersion, target);

            verifyDocCount(target, COLLECTION, OLD_DOCS.length + SHARED_DOCS.length);
            assertDocsPresent(target, OLD_DOCS);
            assertDocsPresent(target, SHARED_DOCS);
            assertDocsAbsent(target, NEW_DOCS);
        }
    }

    /**
     * Standalone: the replication handler writes {@code snapshot.<name>/} beside the data
     * directory. Selecting the first snapshot by name must yield its contents.
     */
    @ParameterizedTest(name = "named standalone: {0} → {1}")
    @MethodSource("standaloneVersions")
    void namedStandaloneBackupMigratesSelectedSnapshot(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            var create = solr.execInContainer("solr", "create_core", "-c", COLLECTION);
            if (create.getExitCode() != 0) {
                throw new IllegalStateException("Failed to create core: " + create.getStderr());
            }

            var dataDir = solrDataDir(solr);
            indexDocs(solr, COLLECTION, OLD_DOCS);
            indexDocs(solr, COLLECTION, SHARED_DOCS);
            standaloneBackup(solr, "snap_v1", dataDir);

            deleteOldDocs(solr);
            indexDocs(solr, COLLECTION, NEW_DOCS);
            standaloneBackup(solr, "snap_v2", dataDir);

            // Deliberately migrate the OLDER snapshot. Copy without the snapshot.<name>/ wrapper
            // so segments_N land at the collection root, which is the flat standalone layout.
            var backupRoot = tempDir.toPath().resolve("named_standalone_" + solrVersion.major());
            var localCollection = backupRoot.resolve(COLLECTION);
            Files.createDirectories(localCollection);
            var find = solr.execInContainer("find", dataDir + "/snapshot.snap_v1", "-type", "f");
            for (var line : find.getStdout().trim().split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                var fileName = line.substring(line.lastIndexOf('/') + 1);
                solr.copyFileFromContainer(line, localCollection.resolve(fileName).toString());
            }

            migrate(backupRoot, solrVersion, target);

            verifyDocCount(target, COLLECTION, OLD_DOCS.length + SHARED_DOCS.length);
            assertDocsPresent(target, OLD_DOCS);
            assertDocsPresent(target, SHARED_DOCS);
            assertDocsAbsent(target, NEW_DOCS);
        }
    }

    // --- Solr setup ---

    private static void createCloudCollection(
        SolrClusterContainer solr, SolrClusterContainer.SolrVersion solrVersion
    ) throws Exception {
        // Solr 6 SolrCloud ships no auto-uploaded configset; Solr 7+ uploads _default itself.
        if (solrVersion.major() == 6) {
            var up = solr.execInContainer(
                "/opt/solr/bin/solr", "zk", "upconfig", "-n", "_default",
                "-d", "/opt/solr/server/solr/configsets/data_driven_schema_configs",
                "-z", "localhost:9983");
            if (up.getExitCode() != 0) {
                throw new IllegalStateException("Solr 6 upconfig failed: " + up.getStderr());
            }
        }
        solr.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + COLLECTION + "&numShards=1&replicationFactor=1"
                + (solrVersion.major() < 9 ? "&maxShardsPerNode=2" : "")
                + (solrVersion.major() == 6 ? "&collection.configName=_default" : "")
                + "&wt=json");
        waitForCollectionActive(solr, 90);
    }

    private static void waitForCollectionActive(SolrClusterContainer solr, int maxSeconds) throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            var status = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection="
                    + COLLECTION + "&wt=json");
            if (status.getStdout().contains("\"state\":\"active\"")) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Collection did not become active within " + maxSeconds + "s");
    }

    /** Solr 6/7 images use /opt/solr/server/solr as SOLR_HOME; 8+ switched to /var/solr/data. */
    private static String solrDataDir(SolrClusterContainer solr) throws Exception {
        var probe = solr.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var dir = probe.getStdout().trim();
        if (dir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }
        return dir;
    }

    // --- Backup operations ---

    private static void cloudBackup(SolrClusterContainer solr, String name, String location) throws Exception {
        var result = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + name + "&collection=" + COLLECTION
                + "&location=" + location + "&wt=json");
        log.info("BACKUP {} response: {}", name, result.getStdout());
        if (result.getStdout().contains("\"status\":500") || result.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("BACKUP " + name + " failed: " + result.getStdout());
        }
        Thread.sleep(2000);
    }

    private static void standaloneBackup(SolrClusterContainer solr, String name, String location) throws Exception {
        solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + COLLECTION
                + "/replication?command=backup&location=" + location + "&name=" + name);
        var snapshotDir = location + "/snapshot." + name;
        for (int i = 0; i < 60; i++) {
            var find = solr.execInContainer("sh", "-c",
                "find " + snapshotDir + " -name 'segments_*' -type f 2>/dev/null | head -1");
            if (!find.getStdout().trim().isEmpty()) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Standalone backup " + name + " produced no segments_* file");
    }

    // --- Documents ---

    private static void indexDocs(SolrClusterContainer solr, String collection, String[][] docs) throws Exception {
        for (String[] doc : docs) {
            var json = String.format("[{\"id\":\"%s\",\"title\":\"%s\"}]", doc[0], doc[1]);
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + collection + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", json);
        }
    }

    private static void deleteOldDocs(SolrClusterContainer solr) throws Exception {
        solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", "{\"delete\":{\"query\":\"id:old-1 OR id:old-2 OR id:old-3\"}}");
    }

    // --- Migration + assertions ---

    private static void migrate(
        Path backupRoot, SolrClusterContainer.SolrVersion solrVersion, SearchClusterContainer target
    ) {
        int exitCode = SourceTestBase.runProcessAgainstTarget(new String[]{
            "--source-version", "SOLR_" + solrVersion.tag(),
            "--snapshot-local-dir", backupRoot.toString(),
            "--snapshot-name", "solr-successive",
            "--target-host", target.getUrl(),
            "--coordinator-host", target.getUrl(),
            "--index-allowlist", COLLECTION,
        });
        assertEquals(0, exitCode, "RfsMigrateDocuments should exit successfully");
    }

    private static void assertDocsPresent(SearchClusterContainer target, String[][] docs) throws Exception {
        var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = restClient(target);
        restClient.get("_refresh", ctx.createUnboundRequestContext());
        for (String[] doc : docs) {
            var resp = restClient.get(COLLECTION + "/_doc/" + doc[0], ctx.createUnboundRequestContext());
            var found = MAPPER.readTree(resp.body).path("found").asBoolean(false);
            assertEquals(true, found, "Doc '" + doc[0] + "' should be present in the migrated backup");
        }
    }

    private static void assertDocsAbsent(SearchClusterContainer target, String[][] docs) throws Exception {
        var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = restClient(target);
        restClient.get("_refresh", ctx.createUnboundRequestContext());
        for (String[] doc : docs) {
            var resp = restClient.get(COLLECTION + "/_doc/" + doc[0], ctx.createUnboundRequestContext());
            var found = MAPPER.readTree(resp.body).path("found").asBoolean(true);
            assertEquals(false, found, "Doc '" + doc[0] + "' is not in the migrated backup");
        }
    }

    private static RestClient restClient(SearchClusterContainer target) {
        return new RestClient(
            ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext());
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
        var restClient = restClient(cluster);
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
