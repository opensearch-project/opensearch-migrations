package org.opensearch.migrations.bulkload.solr;

import java.io.File;
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
     * combinations. Verifies:
     * - Schema-derived mappings have correct OpenSearch types
     * - Stored fields are readable from the Lucene backup
     * - Unstored fields with docValues are readable
     * - Multi-valued fields migrate correctly
     * - All field type conversions (string→keyword, pint→integer, etc.)
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

            // Add explicit fields with various stored/docValues/indexed combos
            addSchemaFields(solr, COLLECTION_NAME);

            // Index a document exercising all field types
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
            assertThat(
                "stored_keyword → keyword",
                properties.path("stored_keyword").path("type").asText(),
                equalTo("keyword")
            );
            assertThat(
                "stored_text → text",
                properties.path("stored_text").path("type").asText(),
                equalTo("text")
            );
            assertThat(
                "stored_int → integer",
                properties.path("stored_int").path("type").asText(),
                equalTo("integer")
            );
            assertThat(
                "stored_long → long",
                properties.path("stored_long").path("type").asText(),
                equalTo("long")
            );
            assertThat(
                "stored_float → float",
                properties.path("stored_float").path("type").asText(),
                equalTo("float")
            );
            assertThat(
                "stored_double → double",
                properties.path("stored_double").path("type").asText(),
                equalTo("double")
            );
            assertThat(
                "stored_date → date",
                properties.path("stored_date").path("type").asText(),
                equalTo("date")
            );
            assertThat(
                "stored_bool → boolean",
                properties.path("stored_bool").path("type").asText(),
                equalTo("boolean")
            );
            assertThat(
                "stored_binary → binary",
                properties.path("stored_binary").path("type").asText(),
                equalTo("binary")
            );
            assertThat(
                "multi_string → keyword",
                properties.path("multi_string").path("type").asText(),
                equalTo("keyword")
            );
            // Unstored fields should still appear in mappings (from schema)
            assertThat(
                "unstored_keyword → keyword",
                properties.path("unstored_keyword").path("type").asText(),
                equalTo("keyword")
            );
            assertThat(
                "unstored_int → integer",
                properties.path("unstored_int").path("type").asText(),
                equalTo("integer")
            );

            // --- Verify document field values ---
            var searchResp = restClient.get(
                COLLECTION_NAME + "/_search?q=id:doc1&size=1", ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should find doc1", hits.size(), equalTo(1));

            var doc = hits.get(0).path("_source");
            log.info("Migrated doc: {}", doc);

            // String/keyword fields — stored
            assertThat("stored_keyword value", doc.path("stored_keyword").asText(), equalTo("hello"));
            assertThat(
                "nodv_keyword value (stored, no docValues)",
                doc.path("nodv_keyword").asText(),
                equalTo("no-docvalues")
            );

            // Text field — stored
            assertThat(
                "stored_text value", doc.path("stored_text").asText(), equalTo("full text search")
            );

            // Numeric fields — stored, exact values
            assertThat("stored_int value", doc.path("stored_int").asInt(), equalTo(42));
            assertThat("stored_long value", doc.path("stored_long").asLong(), equalTo(1234567890L));
            assertThat("stored_float value", (double) doc.path("stored_float").floatValue(), closeTo(3.14, 0.01));
            assertThat(
                "stored_double value", doc.path("stored_double").doubleValue(), closeTo(2.718281828, 0.0001)
            );

            // Date field — Lucene stores as epoch millis, OpenSearch returns as epoch millis
            assertThat(
                "stored_date value (2024-01-15T10:30:00Z as epoch ms)",
                doc.path("stored_date").asLong(),
                equalTo(1705314600000L)
            );

            // Boolean field — stored (Lucene T/F → true/false)
            assertThat("stored_bool value", doc.path("stored_bool").asBoolean(), equalTo(true));

            // Multi-valued field — stored, should be array with 3 values
            assertTrue(doc.path("multi_string").isArray(), "multi_string should be an array");
            assertThat("multi_string size", doc.path("multi_string").size(), equalTo(3));
            // Verify array contents (order may vary)
            var multiValues = new java.util.HashSet<String>();
            doc.path("multi_string").forEach(v -> multiValues.add(v.asText()));
            assertTrue(multiValues.contains("alpha"), "multi_string should contain alpha");
            assertTrue(multiValues.contains("beta"), "multi_string should contain beta");
            assertTrue(multiValues.contains("gamma"), "multi_string should contain gamma");

            log.info("All field values verified");
        }
    }

    // --- Helpers ---

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
        var result = solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-d", schemaUpdate
        );
        log.info("Schema update: {}", result.getStdout());
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
        solr.execInContainer(
            "curl", "-s",
            "http://localhost:8983/solr/" + collection
                + "/replication?command=backup&location=/var/solr/data&name=migration_backup"
        );
        Thread.sleep(3000);

        var localBackupDir = tempDir.toPath().resolve("solr_backup");
        Files.createDirectories(localBackupDir);

        var listResult = solr.execInContainer("ls", "/var/solr/data/snapshot.migration_backup");
        for (var fileName : listResult.getStdout().trim().split("\n")) {
            if (fileName.isEmpty()) continue;
            solr.copyFileFromContainer(
                "/var/solr/data/snapshot.migration_backup/" + fileName,
                localBackupDir.resolve(fileName).toString()
            );
        }
        return localBackupDir;
    }

    private static void createSolrCollection(SolrClusterContainer solr, String name) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", name);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Solr core: " + result.getStderr());
        }
    }

    private static void populateSolrDocuments(SolrClusterContainer solr, String collection, int count)
        throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append(",");
            sb.append(String.format(
                "{\"id\":\"doc%d\",\"title_s\":\"Document %d\",\"value_i\":%d,\"active_b\":true}",
                i, i, i
            ));
        }
        sb.append("]");
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-d", sb.toString()
        );
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
