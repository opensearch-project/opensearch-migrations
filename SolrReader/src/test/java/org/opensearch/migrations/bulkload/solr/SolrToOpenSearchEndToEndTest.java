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
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end tests: Solr backup (Lucene) → pipeline → OpenSearch.
 *
 * <p>Creates a Solr core, indexes documents, fetches schema, takes a backup,
 * then reads via {@link SolrBackupSource} (Lucene + schema) through the pipeline
 * to a real OpenSearch container.
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
    void fullMigrationFromBackup(SolrClusterContainer.SolrVersion solrVersion,
                                  SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion);
             var target = new SearchClusterContainer(targetVersion)) {

            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 10);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(targetClient, null, false,
                DocumentExceptionAllowlist.empty(), null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            verifyDocCount(target, COLLECTION_NAME, 10);
            log.info("Solr backup {} → OS {} migration complete: 10 docs", solrVersion, targetVersion);
        }
    }

    @ParameterizedTest(name = "mappings: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void backupMigrationCreatesMappingsFromSchema(SolrClusterContainer.SolrVersion solrVersion,
                                                   SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion);
             var target = new SearchClusterContainer(targetVersion)) {

            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 3);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(targetClient, null, false,
                DocumentExceptionAllowlist.empty(), null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);
            pipeline.migrateAll().collectList().block();

            // Verify mappings on target
            var restClient = createRestClient(target);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", context.createUnboundRequestContext());

            var resp = restClient.get(COLLECTION_NAME + "/_mapping", context.createUnboundRequestContext());
            assertThat("Mapping request should succeed", resp.statusCode, equalTo(200));

            var properties = MAPPER.readTree(resp.body)
                .path(COLLECTION_NAME).path("mappings").path("properties");
            log.info("OpenSearch mappings: {}", properties);

            // id field should be keyword (from Solr string type)
            assertThat("id should be keyword",
                properties.path("id").path("type").asText(), equalTo("keyword"));

            // Verify docs have content
            var searchResp = restClient.get(COLLECTION_NAME + "/_search?q=*:*&size=1",
                context.createUnboundRequestContext());
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should have hits", hits.size(), greaterThan(0));
            assertNotNull(hits.get(0).path("_source"), "Doc should have _source");

            log.info("Mappings verified: Solr schema → OpenSearch mappings applied");
        }
    }

    // --- Helpers ---

    private static JsonNode fetchSolrSchema(SolrClusterContainer solr, String collection) throws Exception {
        var result = solr.execInContainer(
            "curl", "-s", "http://localhost:8983/solr/" + collection + "/schema?wt=json"
        );
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to fetch schema: " + result.getStderr());
        }
        var schemaResponse = MAPPER.readTree(result.getStdout());
        return schemaResponse.path("schema");
    }

    private Path createAndCopyBackup(SolrClusterContainer solr, String collection) throws Exception {
        solr.execInContainer(
            "curl", "-s",
            "http://localhost:8983/solr/" + collection + "/replication?command=backup&location=/var/solr/data&name=migration_backup"
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
        log.info("Copied Solr backup to {}", localBackupDir);
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
        return new OpenSearchClientFactory(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext()).determineVersionAndCreate();
    }

    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
        restClient.get("_refresh", context.createUnboundRequestContext());
        assertEquals(expected,
            new SearchClusterRequests(context).getMapOfIndexAndDocCount(restClient).getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }
}
