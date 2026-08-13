package org.opensearch.migrations;

import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for Solr backup via testcontainers.
 *
 * <p>The cloud/standalone <em>detection</em> tests run against a single static Solr 8 pair
 * (the logic they exercise is version-independent). The <em>local filesystem backup</em>
 * tests are parameterized across every supported Solr major — 6, 7, 8, and 9 — since the
 * on-disk backup layout and SOLR_HOME location differ by version.
 */
@Slf4j
@Testcontainers
@Tag("isolatedTest")
public class TestCreateSnapshotSolr {

    @Container
    static final SolrClusterContainer STANDALONE_SOLR = new SolrClusterContainer(SolrClusterContainer.SOLR_8);

    @Container
    static final SolrClusterContainer CLOUD_SOLR = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_8);

    /** Every Solr major the backup code supports. */
    static Stream<Arguments> solrVersions() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6),
            Arguments.of(SolrClusterContainer.SOLR_7),
            Arguments.of(SolrClusterContainer.SOLR_8),
            Arguments.of(SolrClusterContainer.SOLR_9)
        );
    }

    /** Solr 6/7 Docker images use /opt/solr/server/solr as SOLR_HOME; 8+ switched to /var/solr/data. */
    private static String solrDataDir(int major) {
        return major <= 7 ? "/opt/solr/server/solr" : "/var/solr/data";
    }

    private static SolrHttpClient clientFor(SolrClusterContainer container) {
        return new SolrHttpClient(connectionContextFor(container));
    }

    private static ConnectionContext connectionContextFor(SolrClusterContainer container) {
        return new ConnectionContext.SourceArgs() {{ host = container.getSolrUrl(); insecure = true; }}.toConnectionContext();
    }

    @Test
    public void testDiscoverCores_standaloneSolr() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var cores = SolrBackupStrategy.discoverCollections(solrUrl, clientFor(STANDALONE_SOLR));

        log.atInfo().setMessage("Discovered standalone cores: {}").addArgument(cores).log();
        Assertions.assertFalse(cores.isEmpty(), "Should discover at least the 'dummy' core");
        Assertions.assertTrue(cores.contains("dummy"), "Should find the 'dummy' core");
    }

    @Test
    public void testDiscoverCollections_solrCloud() throws Exception {
        var solrUrl = CLOUD_SOLR.getSolrUrl();

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=testcoll&numShards=1&replicationFactor=1&wt=json");

        var collections = SolrBackupStrategy.discoverCollections(solrUrl, clientFor(CLOUD_SOLR));

        log.atInfo().setMessage("Discovered SolrCloud collections: {}").addArgument(collections).log();
        Assertions.assertTrue(collections.contains("testcoll"), "Should find 'testcoll' collection");
    }

    @Test
    public void testIsSolrCloud_standalone_returnsFalse() {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        Assertions.assertFalse(SolrBackupStrategy.isSolrCloud(solrUrl, clientFor(STANDALONE_SOLR)),
            "Standalone Solr should not be detected as SolrCloud");
    }

    @Test
    public void testIsSolrCloud_cloud_returnsTrue() {
        var solrUrl = CLOUD_SOLR.getSolrUrl();
        Assertions.assertTrue(SolrBackupStrategy.isSolrCloud(solrUrl, clientFor(CLOUD_SOLR)),
            "SolrCloud should be detected as SolrCloud");
    }

    @ParameterizedTest(name = "Solr {0} standalone → local backup")
    @MethodSource("solrVersions")
    public void testRunSolrBackup_standalone_discoversAndBacksUp(
            SolrClusterContainer.SolrVersion solrVersion) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion)) {
            solr.start();
            int major = solrVersion.major();
            var solrUrl = solr.getSolrUrl();
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

            var args = new CreateSnapshot.Args();
            args.sourceArgs.host = solrUrl;
            args.sourceArgs.insecure = true;
            args.sourceType = "solr";
            args.snapshotName = "test_standalone_backup";
            args.repoUri = solrDataDir(major);
            args.noWait = false;

            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            var result = solr.execInContainer("ls", solrDataDir(major));
            log.atInfo().setMessage("[Solr {}] Backup directory contents: {}")
                .addArgument(solrVersion).addArgument(result.getStdout()).log();
            Assertions.assertTrue(result.getStdout().contains("snapshot.test_standalone_backup"),
                "Backup directory should contain the snapshot for Solr " + solrVersion
                    + "; saw: " + result.getStdout());
        }
    }

    @ParameterizedTest(name = "Solr {0} cloud → local backup")
    @MethodSource("solrVersions")
    public void testRunSolrBackup_cloud_discoversAndBacksUp(
            SolrClusterContainer.SolrVersion solrVersion) throws Exception {
        try (var solr = SolrClusterContainer.cloud(solrVersion)) {
            solr.start();
            int major = solrVersion.major();
            var solrUrl = solr.getSolrUrl();
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

            // Solr 6 ships no default configset, so a SolrCloud CREATE fails with "No config set
            // found" unless we first upload one to ZooKeeper and name it explicitly. Solr 7+ ship
            // a _default configset that CREATE uses automatically.
            String createConfig = "";
            if (major <= 6) {
                var up = solr.execInContainer("/opt/solr/bin/solr", "zk", "upconfig",
                    "-n", "basic",
                    "-d", "/opt/solr/server/solr/configsets/basic_configs/conf",
                    "-z", "localhost:9983");
                if (up.getExitCode() != 0) {
                    throw new IllegalStateException("zk upconfig failed: " + up.getStderr());
                }
                createConfig = "&collection.configName=basic";
            }

            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=cloudcoll&numShards=1&replicationFactor=1" + createConfig + "&wt=json");
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/cloudcoll/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", "[{\"id\":\"doc1\",\"title\":\"test\"}]");

            var backupRoot = solrDataDir(major) + "/backups";
            var args = new CreateSnapshot.Args();
            args.sourceArgs.host = solrUrl;
            args.sourceArgs.insecure = true;
            args.sourceType = "solr";
            args.snapshotName = "test_cloud_backup";
            args.repoUri = backupRoot;
            args.noWait = false;

            // With the per-snapshot location layout (<base>/<snapshotName>), Solr validates that the
            // exact location directory exists before accepting the BACKUP call. Pre-create both the
            // parent and the per-snapshot dir inside the Solr container.
            solr.execInContainer("mkdir", "-p", backupRoot + "/test_cloud_backup");

            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            var result = solr.execInContainer("ls", backupRoot + "/test_cloud_backup");
            log.atInfo().setMessage("[Solr {}] Cloud backup directory contents: {}")
                .addArgument(solrVersion).addArgument(result.getStdout()).log();
            // New layout: <repoUri>/<snapshotName>/<collection>/... — verify collection
            // subdir was created by Solr (not just the empty per-snapshot dir we pre-created).
            Assertions.assertTrue(result.getStdout().contains("cloudcoll"),
                "Backup directory " + backupRoot + "/test_cloud_backup should contain the "
                    + "'cloudcoll' collection subdir after BACKUP completes for Solr " + solrVersion
                    + "; saw: " + result.getStdout());
        }
    }

    // --- Secured source ---

    private static final String SOLR_USER = "backup_user";
    private static final String SOLR_PASS = "backup_pass";

    /**
     * A user holding the backup permissions must be able to drive a full SolrCloud backup, proving
     * credentials actually reach Solr's Collections API rather than only the read paths.
     *
     * <p>Solr's permissions are not hierarchical, so every endpoint on the backup path needs its own
     * grant: LIST and REQUESTSTATUS are {@code collection-admin-read}, BACKUP is
     * {@code collection-admin-edit}.
     *
     * <p>This class starts Solr and runs CreateSnapshot; it does not migrate documents, so the
     * assertion is that the backup lands, not that a migration completes end to end.
     */
    @Test
    public void testBackupCapableUser_securedCloud_backupSucceeds() throws Exception {
        try (var solr = SolrClusterContainer.securedCloud(
                SolrClusterContainer.SOLR_8, SOLR_USER, SOLR_PASS,
                java.util.List.of("read", "schema-read", "collection-admin-read", "collection-admin-edit"))) {
            solr.start();

            var auth = "-u" + SOLR_USER + ":" + SOLR_PASS;
            var create = solr.execInContainer("curl", "-sf", auth,
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=securedcoll&numShards=1&replicationFactor=1&wt=json");
            Assertions.assertEquals(0, create.getExitCode(),
                "CREATE should succeed for the backup-capable user: " + create.getStderr());

            solr.execInContainer("curl", "-s", auth, "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/securedcoll/update?commit=true",
                "-d", "[{\"id\":\"1\",\"title_s\":\"Secured doc\"}]");

            var backupRoot = "/var/solr/data/secured_backups";
            solr.execInContainer("mkdir", "-p", backupRoot + "/secured_snapshot");

            var args = new CreateSnapshot.Args();
            args.sourceArgs.host = solr.getSolrUrl();
            args.sourceArgs.insecure = true;
            args.sourceArgs.username = SOLR_USER;
            args.sourceArgs.password = SOLR_PASS;
            args.sourceType = "solr";
            args.snapshotName = "secured_snapshot";
            args.repoUri = backupRoot;
            args.noWait = false;

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            var listing = solr.execInContainer("ls", backupRoot + "/secured_snapshot");
            log.atInfo().setMessage("Secured backup directory contents: {}")
                .addArgument(listing.getStdout()).log();
            Assertions.assertTrue(listing.getStdout().contains("securedcoll"),
                "Backup driven with credentials should produce the collection subdir; saw: "
                    + listing.getStdout());
        }
    }

    /**
     * Exercises the multi-user/multi-role form of the fixture: two users with different roles must
     * get different access. Asserted at Solr's API rather than through CreateSnapshot so this covers
     * the fixture's security.json generation without depending on topology inference.
     */
    @Test
    public void testSecuredCloud_distinctRolesGetDistinctAccess() throws Exception {
        try (var solr = SolrClusterContainer.securedCloud(
                SolrClusterContainer.SOLR_8,
                java.util.Map.of("reader", "readerpass", "admin", "adminpass"),
                java.util.Map.of(
                    "reader", java.util.List.of("reader_role"),
                    "admin", java.util.List.of("admin_role")),
                java.util.Map.of(
                    "reader_role", java.util.List.of("read", "schema-read"),
                    "admin_role", java.util.List.of(
                        "read", "schema-read", "collection-admin-read", "collection-admin-edit")))) {
            solr.start();

            var createUrl = "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=rolecheck&numShards=1&replicationFactor=1&wt=json";

            Assertions.assertEquals("403", httpStatus(solr, "reader:readerpass", createUrl),
                "reader lacks collection-admin-edit and must be refused CREATE");
            Assertions.assertEquals("200", httpStatus(solr, "admin:adminpass", createUrl),
                "admin holds collection-admin-edit and must be allowed CREATE");
            Assertions.assertEquals("200", httpStatus(solr, "reader:readerpass",
                    "http://localhost:8983/solr/rolecheck/select?q=*:*&rows=0&wt=json"),
                "reader holds read and must be allowed to query");
        }
    }

    /** Returns the HTTP status of an authenticated request issued from inside the container. */
    private static String httpStatus(SolrClusterContainer solr, String userPass, String url) throws Exception {
        var result = solr.execInContainer("curl", "-s", "-o", "/dev/null",
            "-w", "%{http_code}", "-u", userPass, url);
        return result.getStdout().trim();
    }
}
