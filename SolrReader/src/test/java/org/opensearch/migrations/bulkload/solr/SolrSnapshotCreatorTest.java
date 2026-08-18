package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SolrSnapshotCreator}'s backup {@code location} computation.
 * Verifies that the Solr BACKUP API's {@code location} parameter is computed correctly
 * from s3://bucket[/path] and gs://bucket[/path] URIs (the bucket itself is configured in
 * solr.xml, so {@code location} must be the bucket-relative path).
 */
class SolrSnapshotCreatorTest {

    @Test
    void bucketRootReturnsSlash() {
        assertEquals("/", SolrSnapshotCreator.extractS3Path("s3://my-bucket"));
        assertEquals("/", SolrSnapshotCreator.extractS3Path("s3://my-bucket/"));
    }

    @Test
    void singleLevelSubpathIsPreserved() {
        assertEquals("/foo", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo"));
    }

    @Test
    void nestedSubpathIsPreserved() {
        assertEquals("/foo/bar/baz", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo/bar/baz"));
    }

    @Test
    void trailingSlashIsTrimmed() {
        assertEquals("/foo", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo/"));
        assertEquals("/foo/bar", SolrSnapshotCreator.extractS3Path("s3://my-bucket/foo/bar/"));
    }

    @Test
    void subpathWithHyphens() {
        assertEquals("/solr-migration-v3",
            SolrSnapshotCreator.extractS3Path("s3://my-bucket/solr-migration-v3"));
    }

    // extractS3Path is scheme-agnostic (URI.getPath()); confirm it strips a gs:// bucket the same way.
    @Test
    void extractS3Path_gcsUri_stripsSchemeAndBucket() {
        assertEquals("/", SolrSnapshotCreator.extractS3Path("gs://my-bucket"));
        assertEquals("/", SolrSnapshotCreator.extractS3Path("gs://my-bucket/"));
        assertEquals("/foo", SolrSnapshotCreator.extractS3Path("gs://my-bucket/foo"));
        assertEquals("/foo/bar", SolrSnapshotCreator.extractS3Path("gs://my-bucket/foo/bar/"));
    }

    @Test
    void isCloudRepoUri_recognizesS3AndGcs() {
        assertTrue(SolrSnapshotCreator.isCloudRepoUri("s3://my-bucket/foo"));
        assertTrue(SolrSnapshotCreator.isCloudRepoUri("gs://my-bucket/foo"));
        assertFalse(SolrSnapshotCreator.isCloudRepoUri("/var/solr/data"));
        assertFalse(SolrSnapshotCreator.isCloudRepoUri("file:///var/solr/data"));
        assertFalse(SolrSnapshotCreator.isCloudRepoUri(null));
    }

    // buildPerCollectionLocation is what flows into Solr's BACKUP `location` param. For cloud URIs
    // (s3:// AND gs://) it must yield the bucket-relative <path>/<snapshotName>; for a bare
    // filesystem path it appends <snapshotName> to the path as-is.
    @Test
    void buildPerCollectionLocation_gcsBucketRoot() {
        assertEquals("/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("gs://my-bucket", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_gcsSubpath() {
        assertEquals("/migration-v1/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("gs://my-bucket/migration-v1", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_s3SubpathStillWorks() {
        assertEquals("/migration-v1/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("s3://my-bucket/migration-v1", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_filesystemPathAppendsSnapshot() {
        assertEquals("/var/solr/data/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("/var/solr/data", "snap1"));
        assertEquals("/var/solr/data/snap1",
            SolrSnapshotCreator.buildPerCollectionLocation("/var/solr/data/", "snap1"));
    }

    @Test
    void buildPerCollectionLocation_nullLocationReturnsNull() {
        assertNull(SolrSnapshotCreator.buildPerCollectionLocation(null, "snap1"));
    }

    /**
     * Drives the Collections API calls against an in-process {@link HttpServer} to assert the
     * path the BACKUP and REQUESTSTATUS requests are actually sent to.
     */
    @Nested
    class ContextPath {

        private HttpServer server;
        private String baseUrl;
        private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

        @BeforeEach
        void startServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requestedPaths.add(exchange.getRequestURI().getPath());
                var body = "{\"responseHeader\":{\"status\":0},\"status\":{\"state\":\"completed\"}}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
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

        private SolrSnapshotCreator creatorWithContextPath(String contextPath) {
            var connectionContext = new ConnectionContext.SourceArgs() {{
                host = baseUrl;
                insecure = true;
            }}.toConnectionContext();
            return new SolrSnapshotCreator(baseUrl, "snap1", "/var/solr/data",
                List.of("coll1"), connectionContext, null, contextPath);
        }

        @Test
        void nullContextPathKeepsStockSolrPrefix() {
            var creator = creatorWithContextPath(null);
            creator.createSnapshot();
            assertTrue(creator.isSnapshotFinished());
            assertEquals(List.of("/solr/admin/collections", "/solr/admin/collections"), requestedPaths);
        }

        @Test
        void customContextPathIsUsed() {
            var creator = creatorWithContextPath("/tenant-a/solr");
            creator.createSnapshot();
            assertTrue(creator.isSnapshotFinished());
            assertEquals(
                List.of("/tenant-a/solr/admin/collections", "/tenant-a/solr/admin/collections"),
                requestedPaths);
        }

        @Test
        void emptyContextPathServesSolrFromHostRoot() {
            var creator = creatorWithContextPath("");
            creator.createSnapshot();
            assertEquals(List.of("/admin/collections"), requestedPaths);
        }
    }
}
