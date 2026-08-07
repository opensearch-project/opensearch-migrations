package org.opensearch.migrations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrContextPath;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.sun.net.httpserver.HttpServer;
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
        var cores = SolrBackupStrategy.discoverCollections(solrUrl, clientFor(STANDALONE_SOLR), SolrContextPath.DEFAULT);

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

        var collections = SolrBackupStrategy.discoverCollections(solrUrl, clientFor(CLOUD_SOLR), SolrContextPath.DEFAULT);

        log.atInfo().setMessage("Discovered SolrCloud collections: {}").addArgument(collections).log();
        Assertions.assertTrue(collections.contains("testcoll"), "Should find 'testcoll' collection");
    }

    @Test
    public void testIsSolrCloud_standalone_returnsFalse() {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        Assertions.assertFalse(SolrBackupStrategy.isSolrCloud(solrUrl, clientFor(STANDALONE_SOLR), SolrContextPath.DEFAULT),
            "Standalone Solr should not be detected as SolrCloud");
    }

    @Test
    public void testIsSolrCloud_cloud_returnsTrue() {
        var solrUrl = CLOUD_SOLR.getSolrUrl();
        Assertions.assertTrue(SolrBackupStrategy.isSolrCloud(solrUrl, clientFor(CLOUD_SOLR), SolrContextPath.DEFAULT),
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

    /**
     * Real Solr reached through a prefix-rewriting reverse proxy. Reuses the shared standalone
     * container and fronts it with an in-JVM proxy that only answers under /tenant-a/solr, so any
     * URL still hardcoding /solr fails the backup outright.
     */
    @Test
    public void testRunSolrBackup_standalone_behindRewritingProxy() throws Exception {
        var contextPath = "/tenant-a/solr";
        try (var proxy = new RewritingProxy(STANDALONE_SOLR.getSolrUrl(), contextPath)) {
            var args = new CreateSnapshot.Args();
            args.sourceArgs.host = proxy.getUrl();
            args.sourceArgs.insecure = true;
            args.sourceType = "solr";
            args.snapshotName = "test_proxied_backup";
            args.repoUri = solrDataDir(STANDALONE_SOLR.getSolrVersion().major());
            args.noWait = false;
            args.solrContextPath = contextPath;

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            var result = STANDALONE_SOLR.execInContainer("ls",
                solrDataDir(STANDALONE_SOLR.getSolrVersion().major()));
            log.atInfo().setMessage("Proxied backup directory contents: {}")
                .addArgument(result.getStdout()).log();
            Assertions.assertTrue(result.getStdout().contains("snapshot.test_proxied_backup"),
                "Backup taken through the rewritten context path should land in the Solr data dir; saw: "
                    + result.getStdout());
            Assertions.assertTrue(proxy.getForwardedCount() > 0, "proxy should have forwarded requests");
        }
    }

    /**
     * Serves {@code <prefix>/x} by forwarding to {@code <upstream>/solr/x}, mirroring a reverse proxy
     * that rewrites Solr's context path. Anything outside the prefix 404s.
     */
    private static final class RewritingProxy implements AutoCloseable {
        private final HttpServer server;
        private final HttpClient client = HttpClient.newHttpClient();
        private int forwardedCount;

        RewritingProxy(String upstream, String prefix) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    var path = exchange.getRequestURI().getPath();
                    if (!path.startsWith(prefix + "/")) {
                        send(exchange, 404, "{\"error\":\"not served here: " + path + "\"}");
                        return;
                    }
                    var query = exchange.getRequestURI().getRawQuery();
                    var target = upstream + "/solr" + path.substring(prefix.length())
                        + (query == null ? "" : "?" + query);
                    synchronized (this) {
                        forwardedCount++;
                    }
                    var response = client.send(
                        HttpRequest.newBuilder(URI.create(target)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    send(exchange, 500, "{\"error\":\"interrupted\"}");
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        String getUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        synchronized int getForwardedCount() {
            return forwardedCount;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
