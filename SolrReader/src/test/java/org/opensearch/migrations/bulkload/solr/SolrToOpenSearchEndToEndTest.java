package org.opensearch.migrations.bulkload.solr;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests: Solr backup (Lucene) → pipeline → OpenSearch.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class SolrToOpenSearchEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION_NAME = "test_collection";

    @TempDir
    File tempDir;

    static Stream<Arguments> solr8ToOpenSearch() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void fullMigrationFromBackup(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 10);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            verifyDocCount(target, COLLECTION_NAME, 10);
        }
    }

    /**
     * Tests migration of diverse Solr field types with various stored/docValues/indexed
     * combinations.
     */
    @ParameterizedTest(name = "field types: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void migratesAllFieldTypesAndStorageCombinations(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            addSchemaFields(solr, COLLECTION_NAME);
            indexRichDocument(solr, COLLECTION_NAME);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);
            pipeline.migrateAll().collectList().block();

            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // --- Verify mappings ---
            var mappingResp = restClient.get(
                COLLECTION_NAME + "/_mapping", ctx.createUnboundRequestContext()
            );
            var properties = MAPPER.readTree(mappingResp.body)
                .path(COLLECTION_NAME).path("mappings").path("properties");
            log.info("OpenSearch mappings: {}", properties);

            assertThat("id → keyword", properties.path("id").path("type").asText(), equalTo("keyword"));
            assertThat("stored_keyword → keyword", properties.path("stored_keyword").path("type").asText(), equalTo("keyword"));
            assertThat("stored_text → text", properties.path("stored_text").path("type").asText(), equalTo("text"));
            assertThat("stored_int → integer", properties.path("stored_int").path("type").asText(), equalTo("integer"));
            assertThat("stored_long → long", properties.path("stored_long").path("type").asText(), equalTo("long"));
            assertThat("stored_float → float", properties.path("stored_float").path("type").asText(), equalTo("float"));
            assertThat("stored_double → double", properties.path("stored_double").path("type").asText(), equalTo("double"));
            assertThat("stored_date → date", properties.path("stored_date").path("type").asText(), equalTo("date"));
            assertThat("stored_bool → boolean", properties.path("stored_bool").path("type").asText(), equalTo("boolean"));
            assertThat("stored_binary → binary", properties.path("stored_binary").path("type").asText(), equalTo("binary"));
            assertThat("multi_string → keyword", properties.path("multi_string").path("type").asText(), equalTo("keyword"));
            assertThat("unstored_keyword → keyword", properties.path("unstored_keyword").path("type").asText(), equalTo("keyword"));
            assertThat("unstored_int → integer", properties.path("unstored_int").path("type").asText(), equalTo("integer"));

            // --- Verify document field values ---
            var searchResp = restClient.get(
                COLLECTION_NAME + "/_search?q=id:doc1&size=1", ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should find doc1", hits.size(), equalTo(1));

            var doc = hits.get(0).path("_source");
            log.info("Migrated doc: {}", doc);

            assertThat("stored_keyword value", doc.path("stored_keyword").asText(), equalTo("hello"));
            assertThat("nodv_keyword value", doc.path("nodv_keyword").asText(), equalTo("no-docvalues"));
            assertThat("stored_text value", doc.path("stored_text").asText(), equalTo("full text search"));
            assertThat("stored_int value", doc.path("stored_int").asInt(), equalTo(42));
            assertThat("stored_long value", doc.path("stored_long").asLong(), equalTo(1234567890L));
            assertThat("stored_float value", (double) doc.path("stored_float").floatValue(), closeTo(3.14, 0.01));
            assertThat("stored_double value", doc.path("stored_double").doubleValue(), closeTo(2.718281828, 0.0001));
            assertThat("stored_date value", doc.path("stored_date").asLong(), equalTo(1705314600000L));
            assertThat("stored_bool value", doc.path("stored_bool").asBoolean(), equalTo(true));

            assertTrue(doc.path("multi_string").isArray(), "multi_string should be an array");
            assertThat("multi_string size", doc.path("multi_string").size(), equalTo(3));
            var multiValues = new java.util.HashSet<String>();
            doc.path("multi_string").forEach(v -> multiValues.add(v.asText()));
            assertTrue(multiValues.contains("alpha"), "multi_string should contain alpha");
            assertTrue(multiValues.contains("beta"), "multi_string should contain beta");
            assertTrue(multiValues.contains("gamma"), "multi_string should contain gamma");
        }
    }

    /**
     * E2E: SolrClient with Basic Auth sends correct headers and handles 401/403.
     * Uses a real Solr instance to verify authenticated queries work end-to-end,
     * and a mock HTTP server to verify auth failure handling.
     *
     * Note: Solr 8 standalone doesn't support BasicAuthPlugin (SolrCloud-only),
     * so we verify auth header construction and error handling separately.
     */
    @ParameterizedTest(name = "auth: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void basicAuthClientReadsDocuments(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion)) {
            solr.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 5);

            // Client with credentials should work against unauthenticated Solr
            // (Solr ignores the Authorization header when auth is not configured)
            var authedClient = new SolrClient(solr.getSolrUrl(), "user", "pass");
            var collections = authedClient.listCollections();
            assertTrue(collections.contains(COLLECTION_NAME), "Should list collection");

            var response = authedClient.query(COLLECTION_NAME, "*", 10);
            assertThat("Should find 5 docs", response.numFound(), equalTo(5L));

            // Verify 401 handling: start a mock HTTP server that returns 401
            var mockServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(0), 0);
            try {
                mockServer.createContext("/", exchange -> {
                    var authHdr = exchange.getRequestHeaders().getFirst("Authorization");
                    if (authHdr != null && authHdr.equals("Basic "
                            + java.util.Base64.getEncoder().encodeToString("good:pass".getBytes()))) {
                        // Return valid Solr response
                        var body = "{\"collections\":[\"test\"]}";
                        exchange.sendResponseHeaders(200, body.length());
                        exchange.getResponseBody().write(body.getBytes());
                    } else {
                        exchange.sendResponseHeaders(401, -1);
                    }
                    exchange.close();
                });
                mockServer.start();
                var port = mockServer.getAddress().getPort();

                // Good credentials → success
                var goodClient = new SolrClient("http://localhost:" + port, "good", "pass", 0);
                var result = goodClient.listCollections();
                assertTrue(result.contains("test"), "Should succeed with correct auth");

                // Bad credentials → IOException (401)
                var badClient = new SolrClient("http://localhost:" + port, "bad", "creds", 0);
                assertThrows(IOException.class, badClient::listCollections,
                    "Should fail with wrong credentials");

                // No credentials → IOException (401)
                var noAuthClient = new SolrClient("http://localhost:" + port, null, null, 0);
                assertThrows(IOException.class, noAuthClient::listCollections,
                    "Should fail without credentials");
            } finally {
                mockServer.stop(0);
            }
        }
    }

    /**
     * E2E: SolrClient retries on transient server errors.
     */
    @ParameterizedTest(name = "retry: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void clientRetriesOnTransientFailure(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion)) {
            solr.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 3);

            // Client with retries should succeed on a healthy server
            var client = new SolrClient(solr.getSolrUrl(), null, null, 3);
            var response = client.query(COLLECTION_NAME, "*", 10);
            assertThat("Should find 3 docs", response.numFound(), equalTo(3L));

            // Client with 0 retries against unreachable host should fail fast
            var badClient = new SolrClient("http://localhost:1", null, null, 0);
            assertThrows(IOException.class, badClient::listCollections,
                "Should fail immediately with 0 retries");

            // Client with retries against unreachable host should eventually fail
            var retryClient = new SolrClient("http://localhost:1", null, null, 1);
            assertThrows(IOException.class, retryClient::listCollections,
                "Should fail after retries exhausted");
        }
    }

    /**
     * E2E: Multi-shard backup discovery and parallel reading.
     * Creates a simulated multi-shard backup directory structure and verifies
     * all shards are discovered and documents from each shard are migrated.
     */
    @ParameterizedTest(name = "multi-shard: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void multiShardBackupMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            // Create two separate cores to simulate two shards
            var core1 = "shard1_core";
            var core2 = "shard2_core";
            createSolrCollection(solr, core1);
            createSolrCollection(solr, core2);
            populateSolrDocuments(solr, core1, 5, "s1_");
            populateSolrDocuments(solr, core2, 7, "s2_");

            // Create backups of each core
            var backup1 = createAndCopyBackup(solr, core1, "backup1");
            var backup2 = createAndCopyBackup(solr, core2, "backup2");

            // Assemble a multi-shard backup directory
            var multiShardDir = tempDir.toPath().resolve("multi_shard_backup");
            Files.createDirectories(multiShardDir);
            var shard1Dir = multiShardDir.resolve("shard1");
            var shard2Dir = multiShardDir.resolve("shard2");
            copyDirectory(backup1, shard1Dir);
            copyDirectory(backup2, shard2Dir);

            var schema = fetchSolrSchema(solr, core1);
            var source = new SolrBackupSource(multiShardDir, COLLECTION_NAME, schema);

            // Verify shard discovery
            var partitions = source.listPartitions(COLLECTION_NAME);
            assertThat("Should discover 2 shards", partitions.size(), equalTo(2));

            // Migrate all shards with parallel partition processing
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE, 2, 10);
            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have cursors from both shards", cursors.size(), greaterThan(1));
            verifyDocCount(target, COLLECTION_NAME, 12); // 5 + 7
        }
    }

    // --- Helpers ---

    private static void populateSolrDocuments(SolrClusterContainer solr, String collection, int count)
        throws Exception {
        populateSolrDocuments(solr, collection, count, "doc", null, null);
    }

    private static void populateSolrDocuments(SolrClusterContainer solr, String collection, int count, String idPrefix)
        throws Exception {
        populateSolrDocuments(solr, collection, count, idPrefix, null, null);
    }

    private static void populateSolrDocuments(
        SolrClusterContainer solr, String collection, int count, String idPrefix, String user, String pass
    ) throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append(",");
            sb.append(String.format(
                "{\"id\":\"%s%d\",\"title_s\":\"Document %d\",\"value_i\":%d,\"active_b\":true}",
                idPrefix, i, i, i
            ));
        }
        sb.append("]");
        var curlCmd = new java.util.ArrayList<String>();
        curlCmd.add("curl");
        curlCmd.add("-s");
        if (user != null && pass != null) {
            curlCmd.add("-u");
            curlCmd.add(user + ":" + pass);
        }
        curlCmd.add("-H");
        curlCmd.add("Content-Type: application/json");
        curlCmd.add("http://localhost:8983/solr/" + collection + "/update?commit=true");
        curlCmd.add("-d");
        curlCmd.add(sb.toString());
        solr.execInContainer(curlCmd.toArray(new String[0]));
    }

    private static void addSchemaFields(SolrClusterContainer solr, String collection) throws Exception {
        var schemaUpdate = "{"
            + "\"add-field\": ["
            + "  {\"name\":\"stored_keyword\",   \"type\":\"string\",      \"stored\":true,  \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"unstored_keyword\", \"type\":\"string\",      \"stored\":false, \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"stored_text\",      \"type\":\"text_general\", \"stored\":true,  \"indexed\":true},"
            + "  {\"name\":\"unstored_text\",    \"type\":\"text_general\", \"stored\":false, \"indexed\":true},"
            + "  {\"name\":\"stored_int\",       \"type\":\"pint\",         \"stored\":true,  \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"unstored_int\",     \"type\":\"pint\",         \"stored\":false, \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"stored_long\",      \"type\":\"plong\",        \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_float\",     \"type\":\"pfloat\",       \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_double\",    \"type\":\"pdouble\",      \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_date\",      \"type\":\"pdate\",        \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_bool\",      \"type\":\"boolean\",      \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"unstored_bool\",    \"type\":\"boolean\",      \"stored\":false, \"docValues\":true},"
            + "  {\"name\":\"stored_binary\",    \"type\":\"binary\",       \"stored\":true},"
            + "  {\"name\":\"multi_string\",     \"type\":\"strings\",      \"stored\":true,  \"docValues\":true, \"multiValued\":true},"
            + "  {\"name\":\"nodv_keyword\",     \"type\":\"string\",       \"stored\":true,  \"docValues\":false, \"indexed\":true}"
            + "]}";
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-d", schemaUpdate
        );
    }

    private static void indexRichDocument(SolrClusterContainer solr, String collection) throws Exception {
        var doc = "[{"
            + "\"id\": \"doc1\","
            + "\"stored_keyword\": \"hello\","
            + "\"unstored_keyword\": \"invisible\","
            + "\"stored_text\": \"full text search\","
            + "\"unstored_text\": \"not stored\","
            + "\"stored_int\": 42,"
            + "\"unstored_int\": 99,"
            + "\"stored_long\": 1234567890,"
            + "\"stored_float\": 3.14,"
            + "\"stored_double\": 2.718281828,"
            + "\"stored_date\": \"2024-01-15T10:30:00Z\","
            + "\"stored_bool\": true,"
            + "\"unstored_bool\": false,"
            + "\"stored_binary\": \"SGVsbG8gV29ybGQ=\","
            + "\"multi_string\": [\"alpha\", \"beta\", \"gamma\"],"
            + "\"nodv_keyword\": \"no-docvalues\""
            + "}]";
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-d", doc
        );
    }

    private static JsonNode fetchSolrSchema(SolrClusterContainer solr, String collection) throws Exception {
        var result = solr.execInContainer(
            "curl", "-s", "http://localhost:8983/solr/" + collection + "/schema?wt=json"
        );
        return MAPPER.readTree(result.getStdout()).path("schema");
    }

    private Path createAndCopyBackup(SolrClusterContainer solr, String collection) throws Exception {
        return createAndCopyBackup(solr, collection, "migration_backup");
    }

    private Path createAndCopyBackup(SolrClusterContainer solr, String collection, String backupName)
        throws Exception {
        solr.execInContainer(
            "curl", "-s",
            "http://localhost:8983/solr/" + collection
                + "/replication?command=backup&location=/var/solr/data&name=" + backupName
        );
        Thread.sleep(3000);

        var snapshotDir = "/var/solr/data/snapshot." + backupName;
        var localBackupDir = tempDir.toPath().resolve("solr_backup_" + backupName);
        Files.createDirectories(localBackupDir);

        var listResult = solr.execInContainer("ls", snapshotDir);
        for (var fileName : listResult.getStdout().trim().split("\n")) {
            if (fileName.isEmpty()) continue;
            solr.copyFileFromContainer(
                snapshotDir + "/" + fileName,
                localBackupDir.resolve(fileName).toString()
            );
        }
        return localBackupDir;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            stream.forEach(p -> {
                try {
                    Files.copy(p, target.resolve(p.getFileName()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void createSolrCollection(SolrClusterContainer solr, String name) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", name);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Solr core: " + result.getStderr());
        }
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createOpenSearchClient(
        SearchClusterContainer cluster
    ) {
        return new OpenSearchClientFactory(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        ).determineVersionAndCreate();
    }

    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        );
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
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
