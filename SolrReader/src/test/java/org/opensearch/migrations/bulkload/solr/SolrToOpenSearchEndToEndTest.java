package org.opensearch.migrations.bulkload.solr;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end tests: Solr → pipeline → OpenSearch.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class SolrToOpenSearchEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION_NAME = "test_collection";

    static Stream<Arguments> solr8ToOpenSearch() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void fullMigrationWithDocuments(SolrClusterContainer.SolrVersion solrVersion,
                                    SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion);
             var target = new SearchClusterContainer(targetVersion)) {

            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 10);

            var solrClient = new SolrClient(solr.getSolrUrl());
            var source = new SolrDocumentSource(solrClient);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(targetClient, null, false,
                DocumentExceptionAllowlist.empty(), null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            verifyDocCount(target, COLLECTION_NAME, 10);
            log.info("Solr {} → OS {} migration complete: 10 docs", solrVersion, targetVersion);
        }
    }

    @ParameterizedTest(name = "batching: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void migrationWithSmallBatches(SolrClusterContainer.SolrVersion solrVersion,
                                   SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion);
             var target = new SearchClusterContainer(targetVersion)) {

            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 7);

            var solrClient = new SolrClient(solr.getSolrUrl());
            var source = new SolrDocumentSource(solrClient, 3);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(targetClient, null, false,
                DocumentExceptionAllowlist.empty(), null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 2, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have multiple batches", cursors.size(), greaterThanOrEqualTo(2));
            verifyDocCount(target, COLLECTION_NAME, 7);
        }
    }

    @ParameterizedTest(name = "metadata: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void metadataMigrationCreatesIndexWithMappings(SolrClusterContainer.SolrVersion solrVersion,
                                                    SearchClusterContainer.ContainerVersion targetVersion) throws Exception {
        try (var solr = new SolrClusterContainer(solrVersion);
             var target = new SearchClusterContainer(targetVersion)) {

            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            // Index a doc so Solr schema has concrete field types
            populateSolrDocuments(solr, COLLECTION_NAME, 1);

            // Migrate docs (which also creates the index with metadata)
            var solrClient = new SolrClient(solr.getSolrUrl());
            var source = new SolrDocumentSource(solrClient);
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(targetClient, null, false,
                DocumentExceptionAllowlist.empty(), null);
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);
            pipeline.migrateAll().collectList().block();

            // Verify the index exists and has mappings from Solr schema
            var restClient = createRestClient(target);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get(COLLECTION_NAME + "/_mapping", context.createUnboundRequestContext());
            assertThat("Mapping request should succeed", resp.statusCode, equalTo(200));

            var mappingJson = MAPPER.readTree(resp.body);
            var properties = mappingJson.path(COLLECTION_NAME).path("mappings").path("properties");
            log.info("OpenSearch mappings for {}: {}", COLLECTION_NAME, properties);

            // Verify key fields from Solr schema were mapped
            assertNotNull(properties.get("id"), "id field should exist in mappings");
            assertNotNull(properties.get("title"), "title field should exist in mappings");

            // Verify the id field is keyword (from Solr string type)
            assertThat("id should be keyword type",
                properties.path("id").path("type").asText(), equalTo("keyword"));

            log.info("Metadata migration {} → {} verified: mappings present on target",
                solrVersion, targetVersion);
        }
    }

    // --- Helpers ---

    private static void createSolrCollection(SolrClusterContainer solr, String name) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", name);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Solr core: " + result.getStderr());
        }
        log.info("Created Solr core: {}", name);
    }

    private static void populateSolrDocuments(SolrClusterContainer solr, String collection, int count)
        throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append(",");
            sb.append(String.format(
                "{\"id\":\"doc%d\",\"title\":\"Document %d\",\"value\":%d,\"active\":true}",
                i, i, i
            ));
        }
        sb.append("]");

        var result = solr.execInContainer(
            "curl", "-s",
            "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-d", sb.toString()
        );
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to index documents: " + result.getStderr());
        }
        log.info("Indexed {} documents into Solr collection {}", count, collection);
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createOpenSearchClient(
        SearchClusterContainer cluster
    ) {
        var connectionContext = ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext();
        return new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
    }

    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(ConnectionContextTestParams.builder()
            .host(cluster.getUrl()).build().toConnectionContext());
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
        restClient.get("_refresh", context.createUnboundRequestContext());
        var requests = new SearchClusterRequests(context);
        var counts = requests.getMapOfIndexAndDocCount(restClient);
        assertEquals(expected, counts.getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName);
    }
}
