package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No-mock tests for {@link SolrStandaloneBackupCreator}.
 *
 * <p>Spins up an in-process JDK {@link HttpServer} that serves real-shape responses
 * from Solr's {@code /replication?command=backup} and {@code command=details}
 * endpoints, then drives the creator against it. A {@link TempDir} is used as the
 * backup target path so the {@code location} URL parameter sent to the handler is
 * a realistic on-disk directory.
 */
class SolrStandaloneBackupCreatorTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> getResponses = new ConcurrentHashMap<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestedQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new RecordingHandler());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ConnectionContext noAuthContext(String url) {
        return new ConnectionContext.SourceArgs() {{ host = url; insecure = true; }}.toConnectionContext();
    }

    @Test
    void constructsWithoutRepository() {
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "/var/solr/data",
            List.of("core1"), noAuthContext("http://localhost:8983"));
        assertThat(creator.getBackupName(), equalTo("backup"));
    }

    @Test
    void constructsWithRepository() {
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983", "backup", "s3://bucket/path",
            List.of("core1"), noAuthContext("http://localhost:8983"), "s3repo");
        assertThat(creator.getBackupName(), equalTo("backup"));
    }

    @Test
    void trailingSlashOnBaseUrlIsStripped() {
        // Constructor strips a trailing slash from the base URL so URL composition
        // doesn't produce double slashes when "/solr/<core>/replication..." is appended.
        var creator = new SolrStandaloneBackupCreator(
            "http://localhost:8983/", "backup", "/var/solr/data",
            List.of("core1"), noAuthContext("http://localhost:8983"));
        assertThat(creator.getBackupName(), equalTo("backup"));
    }

    @Test
    void createBackup_success_hitsReplicationHandlerForEachCore(@TempDir Path tempDir) {
        getResponses.put("/solr/core_a/replication", "{\"status\":\"OK\"}");
        getResponses.put("/solr/core_b/replication", "{\"status\":\"OK\"}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap1", tempDir.toString(),
            List.of("core_a", "core_b"), noAuthContext(baseUrl));
        creator.createBackup();

        assertThat(requestedPaths, hasItem("/solr/core_a/replication"));
        assertThat(requestedPaths, hasItem("/solr/core_b/replication"));
        // Each query should carry the backup parameters and the TempDir-backed location.
        var encodedLocation = URLEncoder.encode(tempDir.toString(), StandardCharsets.UTF_8)
            // Solr code emits the location verbatim — assert the substring as-sent.
            .replace("+", "%20");
        var seenAnyWithLocation = requestedQueries.stream()
            .anyMatch(q -> q.contains("command=backup")
                && q.contains("name=snap1")
                && (q.contains("location=" + tempDir.toString())
                    || q.contains("location=" + encodedLocation)));
        assertTrue(seenAnyWithLocation,
            "Expected a backup request with location=" + tempDir + ", got: " + requestedQueries);
        // No repository was configured; the handler must not have received the repository param.
        assertThat(requestedQueries.toString(), not(containsString("repository=")));
    }

    @Test
    void createBackup_withRepository_includesRepoParameter(@TempDir Path tempDir) {
        getResponses.put("/solr/c1/replication", "{\"status\":\"OK\"}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap-r", tempDir.toString(),
            List.of("c1"), noAuthContext(baseUrl), "myrepo");
        creator.createBackup();

        assertThat(requestedQueries.size(), is(1));
        assertThat(requestedQueries.get(0), containsString("repository=myrepo"));
    }

    @Test
    void createBackup_nonOkStatus_throwsSolrBackupFailed(@TempDir Path tempDir) {
        getResponses.put("/solr/bad_core/replication",
            "{\"status\":\"ERROR\",\"message\":\"snapshot already exists\"}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "dup", tempDir.toString(),
            List.of("bad_core"), noAuthContext(baseUrl));

        var ex = assertThrows(SolrSnapshotCreator.SolrBackupFailed.class, creator::createBackup);
        assertThat(ex.getMessage(), containsString("bad_core"));
        assertThat(ex.getMessage(), containsString("snapshot already exists"));
    }

    @Test
    void isBackupFinished_returnsFalseWhenBackupNodeMissing(@TempDir Path tempDir) {
        // details.backup is absent — the creator treats this as "not started yet".
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"indexSize\":\"0 bytes\"}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));
        assertFalse(creator.isBackupFinished());
    }

    @Test
    void isBackupFinished_returnsTrueOnSuccessAcrossAllCores(@TempDir Path tempDir) {
        // Real Solr emits backup details as a NamedList serialized to a JSON array.
        // Provide success on both cores; the loop should advance through all and return true.
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":["
                + "\"startTime\",\"2024-01-01T00:00:00Z\","
                + "\"snapshotName\",\"snap\","
                + "\"status\",\"success\""
                + "]}}");
        getResponses.put("/solr/core2/replication",
            "{\"details\":{\"backup\":["
                + "\"snapshotName\",\"snap\","
                + "\"status\",\"success\""
                + "]}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1", "core2"), noAuthContext(baseUrl));
        assertTrue(creator.isBackupFinished());
    }

    @Test
    void isBackupFinished_returnsFalseWhileInProgress(@TempDir Path tempDir) {
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":["
                + "\"snapshotName\",\"snap\","
                + "\"status\",\"In Progress\""
                + "]}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));
        assertFalse(creator.isBackupFinished());
    }

    @Test
    void isBackupFinished_throwsWhenStatusIsFailed(@TempDir Path tempDir) {
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":["
                + "\"snapshotName\",\"snap\","
                + "\"status\",\"failed\","
                + "\"exception\",\"disk full\""
                + "]}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));

        var ex = assertThrows(SolrSnapshotCreator.SolrBackupFailed.class, creator::isBackupFinished);
        assertThat(ex.getMessage(), containsString("core1"));
        assertThat(ex.getMessage(), containsString("disk full"));
    }

    @Test
    void isBackupFinished_throwsWhenStatusIsExceptionWithoutDetail(@TempDir Path tempDir) {
        // status=exception with no "exception" key in the NamedList — the message should
        // fall back to the status string itself (exercises the null-message branch).
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":["
                + "\"snapshotName\",\"snap\","
                + "\"status\",\"exception\""
                + "]}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));

        var ex = assertThrows(SolrSnapshotCreator.SolrBackupFailed.class, creator::isBackupFinished);
        assertThat(ex.getMessage(), containsString("exception"));
    }

    @Test
    void isBackupFinished_unknownStatusIsTreatedAsInProgress(@TempDir Path tempDir) {
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":["
                + "\"snapshotName\",\"snap\","
                + "\"status\",\"queued\""
                + "]}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));
        assertFalse(creator.isBackupFinished());
    }

    @Test
    void isBackupFinished_nullStatusFromMissingKeyIsInProgress(@TempDir Path tempDir) {
        // backup NamedList present but no "status" entry — extractNamedListValue returns
        // null and the creator treats null as "still in progress".
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":["
                + "\"snapshotName\",\"snap\","
                + "\"startTime\",\"2024-01-01T00:00:00Z\""
                + "]}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));
        assertFalse(creator.isBackupFinished());
    }

    @Test
    void isBackupFinished_nonArrayBackupNodeIsTreatedAsInProgress(@TempDir Path tempDir) {
        // backup is a JSON object, not the expected NamedList array. The non-array
        // branch in extractNamedListValue returns null and we fall through to in-progress.
        getResponses.put("/solr/core1/replication",
            "{\"details\":{\"backup\":{\"status\":\"success\"}}}");

        var creator = new SolrStandaloneBackupCreator(
            baseUrl, "snap", tempDir.toString(),
            List.of("core1"), noAuthContext(baseUrl));
        assertFalse(creator.isBackupFinished());
    }

    /**
     * Records every request and serves a per-path canned JSON body as
     * application/json. Returns 404 if no canned response is registered.
     */
    private class RecordingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                var path = exchange.getRequestURI().getPath();
                var query = exchange.getRequestURI().getRawQuery();
                requestedPaths.add(path);
                requestedQueries.add(query == null ? "" : query);

                var body = getResponses.get(path);
                if (body == null) {
                    var notFound = "{\"error\":\"no canned response for " + path + "\"}";
                    var bytes = notFound.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    return;
                }
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        }
    }

}
