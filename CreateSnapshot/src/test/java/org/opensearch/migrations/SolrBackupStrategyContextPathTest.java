package org.opensearch.migrations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrContextPath;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link SolrBackupStrategy} against an in-process Solr stand-in to assert every URL it
 * builds honors the configured context path. Covers the standalone CREATE path end to end:
 * cloud-vs-standalone detection, core discovery, schema fetch, and the replication backup call.
 */
class SolrBackupStrategyContextPathTest {

    private static final String CONTEXT_PATH = "/tenant-a/solr";

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getQuery();
            requestedPaths.add(path);
            var response = responseFor(path, query);
            var body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record StubResponse(int status, String body) {}

    /** Answers only under CONTEXT_PATH; anything else 404s, so a wrong prefix fails the test. */
    private static StubResponse responseFor(String path, String query) {
        if (!path.startsWith(CONTEXT_PATH + "/")) {
            return new StubResponse(404, "{\"error\":\"wrong context path: " + path + "\"}");
        }
        var suffix = path.substring(CONTEXT_PATH.length());
        if (suffix.equals("/admin/collections")) {
            // Standalone's verbatim rejection: the positive marker detection matches on.
            return new StubResponse(400,
                "{\"error\":{\"msg\":\"Solr instance is not running in SolrCloud mode.\"}}");
        }
        if (suffix.equals("/admin/cores")) {
            return new StubResponse(200, "{\"status\":{\"core1\":{}}}");
        }
        if (suffix.equals("/core1/admin/file")) {
            return new StubResponse(200, "<schema name=\"core1\"/>");
        }
        if (suffix.equals("/core1/replication") && query != null && query.contains("command=backup")) {
            return new StubResponse(200, "{\"status\":\"OK\"}");
        }
        return new StubResponse(404, "{\"error\":\"unexpected: " + suffix + "\"}");
    }

    private CreateSnapshot.Args argsFor(Path repoDir, String contextPath) {
        var args = new CreateSnapshot.Args();
        args.snapshotName = "snap1";
        args.snapshotRepoName = "repo";
        args.repoUri = "file://" + repoDir;
        args.noWait = true;
        args.solrContextPath = contextPath;
        args.sourceArgs.host = baseUrl;
        args.sourceArgs.insecure = true;
        return args;
    }

    private SolrHttpClient httpClient() {
        return new SolrHttpClient(
            new ConnectionContext.SourceArgs() {{ host = baseUrl; insecure = true; }}.toConnectionContext());
    }

    @Test
    void standaloneCreateBackupUsesContextPathForEveryCall(@TempDir Path repoDir) {
        new SolrBackupStrategy(argsFor(repoDir, CONTEXT_PATH)).run();

        assertThat(requestedPaths, everyItem(startsWith(CONTEXT_PATH + "/")));
        assertThat(requestedPaths, hasItem(CONTEXT_PATH + "/admin/collections"));
        assertThat(requestedPaths, hasItem(CONTEXT_PATH + "/admin/cores"));
        assertThat(requestedPaths, hasItem(CONTEXT_PATH + "/core1/admin/file"));
        assertThat(requestedPaths, hasItem(CONTEXT_PATH + "/core1/replication"));

        // The schema fetched over the custom path is what lands in the synthetic zk_backup layout.
        var schema = repoDir.resolve("snap1/core1/zk_backup_0/configs/core1/managed-schema.xml");
        assertTrue(Files.exists(schema), "expected schema written to " + schema);
    }

    @Test
    void trailingSlashOnContextPathIsNormalized(@TempDir Path repoDir) {
        new SolrBackupStrategy(argsFor(repoDir, CONTEXT_PATH + "/")).run();

        // Without normalization these would be "/tenant-a/solr//admin/cores" and 404.
        assertThat(requestedPaths, hasItem(CONTEXT_PATH + "/admin/cores"));
        assertThat(requestedPaths, hasItem(CONTEXT_PATH + "/core1/replication"));
    }

    @Test
    void discoveryHelpersHonorContextPath() throws IOException {
        assertFalse(SolrBackupStrategy.isSolrCloud(baseUrl, httpClient(), CONTEXT_PATH),
            "the stub rejects LIST the way standalone Solr does, so the source must read as standalone");
        assertThat(SolrBackupStrategy.discoverCollections(baseUrl, httpClient(), null, CONTEXT_PATH).names(),
            equalTo(List.of("core1")));
        assertThat(requestedPaths, everyItem(startsWith(CONTEXT_PATH + "/")));
    }

    @Test
    void defaultContextPathDoesNotReachACustomPrefix() {
        // Guards the backward-compatibility claim: the stock default must not hit CONTEXT_PATH.
        // The prefix answers nothing, so topology is unidentifiable — that must fail loudly
        // rather than fall through to standalone and back up the wrong thing.
        assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(baseUrl, httpClient(), SolrContextPath.DEFAULT));
        assertThat(requestedPaths, hasItem("/solr/admin/collections"));
    }
}
