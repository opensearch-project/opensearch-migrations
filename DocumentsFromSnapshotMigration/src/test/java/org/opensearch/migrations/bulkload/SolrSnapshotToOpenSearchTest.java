package org.opensearch.migrations.bulkload;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.solr.SolrBackupSource;
import org.opensearch.migrations.bulkload.solr.SolrClient;
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

import java.util.stream.Stream;

/**
 * E2E: Solr 8 backup (Lucene snapshot) → pipeline → OpenSearch 3.
 * Validates the snapshot-based document migration path for Solr sources.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class SolrSnapshotToOpenSearchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION_NAME = "movies";

    @TempDir
    File tempDir;

    static Stream<Arguments> solr8ToOpenSearch3() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V3_0_0)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("solr8ToOpenSearch3")
    void snapshotBasedDocumentMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            // Create collection and index documents
            createSolrCore(solr, COLLECTION_NAME);
            indexMovieDocuments(solr, COLLECTION_NAME);

            // Fetch schema and create backup
            var schema = fetchSchema(solr, COLLECTION_NAME);
            var backupDir = createBackup(solr, COLLECTION_NAME);

            // Migrate via pipeline
            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema);
            var targetClient = new OpenSearchClientFactory(
                ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext()
            ).determineVersionAndCreate();
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            verifyDocCount(target, COLLECTION_NAME, 5);

            // Verify a specific document
            var restClient = new RestClient(
                ConnectionContextTestParams.builder().host(target.getUrl()).build().toConnectionContext()
            );
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());
            var resp = restClient.get(
                COLLECTION_NAME + "/_search?q=title:Inception&size=1",
                ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(resp.body).path("hits").path("hits");
            assertThat("Should find Inception", hits.size(), equalTo(1));
            assertThat(hits.get(0).path("_source").path("director").asText(), equalTo("Christopher Nolan"));
        }
    }

    // --- Helpers ---

    private static void createSolrCore(SolrClusterContainer solr, String name) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", name);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Solr core: " + result.getStderr());
        }
    }

    private static void indexMovieDocuments(SolrClusterContainer solr, String collection) throws Exception {
        String[][] movies = {
            {"1", "Inception", "Christopher Nolan", "sci-fi", "2010", "8.8"},
            {"2", "The Matrix", "Wachowski Sisters", "sci-fi", "1999", "8.7"},
            {"3", "Pulp Fiction", "Quentin Tarantino", "crime", "1994", "8.9"},
            {"4", "The Godfather", "Francis Ford Coppola", "crime", "1972", "9.2"},
            {"5", "Interstellar", "Christopher Nolan", "sci-fi", "2014", "8.6"},
        };
        for (String[] m : movies) {
            String doc = String.format(
                "[{\"id\":\"%s\",\"title\":\"%s\",\"director\":\"%s\",\"genre\":\"%s\",\"year\":%s,\"rating\":%s}]",
                m[0], m[1], m[2], m[3], m[4], m[5]
            );
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + collection + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", doc
            );
        }
    }

    private static JsonNode fetchSchema(SolrClusterContainer solr, String collection) throws Exception {
        var result = solr.execInContainer(
            "curl", "-s", "http://localhost:8983/solr/" + collection + "/schema?wt=json"
        );
        return MAPPER.readTree(result.getStdout()).path("schema");
    }

    private Path createBackup(SolrClusterContainer solr, String collection) throws Exception {
        solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + collection
                + "/replication?command=backup&location=/var/solr/data&name=migration_backup"
        );
        Thread.sleep(3000);

        var snapshotDir = "/var/solr/data/snapshot.migration_backup";
        var localBackupDir = tempDir.toPath().resolve("solr_backup");
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

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = new RestClient(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        );
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
