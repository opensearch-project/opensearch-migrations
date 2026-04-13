package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationBootstrap;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.solr.SolrBackupIndexMetadataFactory;
import org.opensearch.migrations.bulkload.solr.SolrBackupSource;
import org.opensearch.migrations.bulkload.solr.SolrMultiCollectionSource;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.ShardWorkPreparer;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
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

/**
 * E2E: Solr 8 backup (Lucene snapshot) → RfsMigrateDocuments → OpenSearch 3.
 * Validates the snapshot-based document migration path for Solr sources,
 * wired through the RfsMigrateDocuments CLI entry point.
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
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V3_5_0, false),
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V3_5_0, true)
        );
    }

    /**
     * Tests the full RfsMigrateDocuments CLI path: detects Solr version,
     * reads backup from local dir, fetches schema from source, migrates docs.
     */
    @ParameterizedTest(name = "{0} → {1} (cloud={2})")
    @MethodSource("solr8ToOpenSearch3")
    void snapshotBasedDocumentMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion,
        boolean cloudMode
    ) throws Exception {
        try (
            var solr = createSolr(solrVersion, cloudMode);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createCollection(solr, COLLECTION_NAME);
            indexMovieDocuments(solr, COLLECTION_NAME);

            var backupDir = createBackup(solr, COLLECTION_NAME);

            int exitCode = SourceTestBase.runProcessAgainstTarget(new String[]{
                "--source-version", "SOLR_8.11.4",
                "--snapshot-local-dir", backupDir.toString(),
                "--snapshot-name", "solr-migration",
                "--target-host", target.getUrl(),
                "--coordinator-host", target.getUrl(),
                "--index-allowlist", COLLECTION_NAME
            });
            assertEquals(0, exitCode, "RfsMigrateDocuments should exit successfully");

            verifyDocCount(target, COLLECTION_NAME, 5);

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

    /**
     * Tests the pipeline-level integration directly (no CLI).
     */
    @ParameterizedTest(name = "pipeline: {0} → {1} (cloud={2})")
    @MethodSource("solr8ToOpenSearch3")
    void pipelineLevelMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion,
        boolean cloudMode
    ) throws Exception {
        try (
            var solr = createSolr(solrVersion, cloudMode);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createCollection(solr, COLLECTION_NAME);
            indexMovieDocuments(solr, COLLECTION_NAME);

            var schema = fetchSchema(solr, COLLECTION_NAME);
            var backupRoot = createBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupRoot.resolve(COLLECTION_NAME), COLLECTION_NAME, schema);
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
        }
    }

    /**
     * Tests the full coordinator-based flow: work items are created per shard,
     * acquired via lease, and processed one at a time — matching the ES backfill path.
     */
    @ParameterizedTest(name = "coordinator: {0} → {1} (cloud={2})")
    @MethodSource("solr8ToOpenSearch3")
    void coordinatorBasedMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion,
        boolean cloudMode
    ) throws Exception {
        try (
            var solr = createSolr(solrVersion, cloudMode);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createCollection(solr, COLLECTION_NAME);
            indexMovieDocuments(solr, COLLECTION_NAME);

            var schema = fetchSchema(solr, COLLECTION_NAME);
            var backupDir = createBackup(solr, COLLECTION_NAME);

            var connectionContext = ConnectionContextTestParams.builder()
                .host(target.getUrl()).build().toConnectionContext();
            var targetClient = new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
            var targetVersion_ = targetClient.getClusterVersion();

            var schemas = Map.<String, JsonNode>of(COLLECTION_NAME, MAPPER.createObjectNode().set("schema", schema));
            var indexMetadataFactory = new SolrBackupIndexMetadataFactory(backupDir, schemas);
            var documentSource = new SolrMultiCollectionSource(backupDir, schemas);

            var coordinatorFactory = new WorkCoordinatorFactory(targetVersion_);
            var progressCursor = new AtomicReference<WorkItemCursor>();
            var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();
            var testContext = DocumentMigrationTestContext.factory().noOtelTracking();

            try (var workCoordinator = coordinatorFactory.get(
                     new CoordinateWorkHttpClient(connectionContext),
                     SourceTestBase.TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                     UUID.randomUUID().toString(),
                     Clock.systemUTC(),
                     workItemRef::set);
                 var processManager = new LeaseExpireTrigger(w -> {
                     log.atDebug().setMessage("Lease expired for {} (test)").addArgument(w).log();
                 })) {

                var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, processManager);

                // Set up work items (like ShardWorkPreparer does for ES)
                new ShardWorkPreparer().run(
                    scopedWorkCoordinator, indexMetadataFactory,
                    "solr-test", List.of(COLLECTION_NAME), testContext
                );

                // Process shards one at a time until no work left
                int shardsProcessed = 0;
                while (true) {
                    var runner = DocumentMigrationBootstrap.builder()
                        .targetClient(targetClient)
                        .snapshotName("solr-test")
                        .maxDocsPerBatch(1000)
                        .maxBytesPerBatch(Long.MAX_VALUE)
                        .batchConcurrency(10)
                        .allowlist(DocumentExceptionAllowlist.empty())
                        .externalDocumentSource(documentSource)
                        .workCoordinator(scopedWorkCoordinator)
                        .maxInitialLeaseDuration(Duration.ofMinutes(5))
                        .cursorConsumer(progressCursor::set)
                        .cancellationTriggerConsumer(r -> {})
                        .build();

                    var status = runner.migrateOneShard(testContext::createReindexContext);
                    if (status == CompletionStatus.NOTHING_DONE) break;
                    shardsProcessed++;
                }

                assertThat("Should have processed at least 1 shard", shardsProcessed, greaterThan(0));
            }

            verifyDocCount(target, COLLECTION_NAME, 5);

            // Verify specific document content
            var restClient = new RestClient(connectionContext);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());
            var resp = restClient.get(
                COLLECTION_NAME + "/_search?q=title:Inception&size=1",
                ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(resp.body).path("hits").path("hits");
            assertThat("Should find Inception", hits.size(), equalTo(1));
        }
    }

    /**
     * Tests migrating multiple Solr collections through the work coordinator.
     * Each collection is a single shard, so migrateOneShard() should be called
     * exactly once per collection (2 total), then return NOTHING_DONE.
     */
    @ParameterizedTest(name = "multi-collection: {0} → {1} (cloud={2})")
    @MethodSource("solr8ToOpenSearch3")
    void multiCollectionMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion,
        boolean cloudMode
    ) throws Exception {
        try (
            var solr = createSolr(solrVersion, cloudMode);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createCollection(solr, "movies");
            createCollection(solr, "books");

            // Index documents
            indexMovieDocuments(solr, "movies");
            indexBookDocuments(solr, "books");

            // Fetch schemas
            var moviesSchema = fetchSchema(solr, "movies");
            var booksSchema = fetchSchema(solr, "books");

            // Create backups into the same backupRoot directory
            var backupRoot = createBackup(solr, "movies", "backup_movies");
            createBackup(solr, "books", "backup_books");

            // Build multi-collection source and metadata factory
            var schemas = Map.<String, JsonNode>of(
                "movies", MAPPER.createObjectNode().set("schema", moviesSchema),
                "books", MAPPER.createObjectNode().set("schema", booksSchema)
            );
            var indexMetadataFactory = new SolrBackupIndexMetadataFactory(backupRoot, schemas);
            var documentSource = new SolrMultiCollectionSource(backupRoot, schemas);

            var connectionContext = ConnectionContextTestParams.builder()
                .host(target.getUrl()).build().toConnectionContext();
            var targetClient = new OpenSearchClientFactory(connectionContext).determineVersionAndCreate();
            var targetVersion_ = targetClient.getClusterVersion();

            var coordinatorFactory = new WorkCoordinatorFactory(targetVersion_);
            var progressCursor = new AtomicReference<WorkItemCursor>();
            var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();
            var testContext = DocumentMigrationTestContext.factory().noOtelTracking();

            try (var workCoordinator = coordinatorFactory.get(
                     new CoordinateWorkHttpClient(connectionContext),
                     SourceTestBase.TOLERABLE_CLIENT_SERVER_CLOCK_DIFFERENCE_SECONDS,
                     UUID.randomUUID().toString(),
                     Clock.systemUTC(),
                     workItemRef::set);
                 var processManager = new LeaseExpireTrigger(w -> {
                     log.atDebug().setMessage("Lease expired for {} (test)").addArgument(w).log();
                 })) {

                var scopedWorkCoordinator = new ScopedWorkCoordinator(workCoordinator, processManager);

                // Register work items for both collections
                new ShardWorkPreparer().run(
                    scopedWorkCoordinator, indexMetadataFactory,
                    "solr-multi", List.of("movies", "books"), testContext
                );

                // Process one shard at a time — each call should migrate exactly one shard
                int shardsProcessed = 0;
                for (int attempt = 0; attempt < 10; attempt++) {
                    var runner = DocumentMigrationBootstrap.builder()
                        .targetClient(targetClient)
                        .snapshotName("solr-multi")
                        .maxDocsPerBatch(1000)
                        .maxBytesPerBatch(Long.MAX_VALUE)
                        .batchConcurrency(10)
                        .allowlist(DocumentExceptionAllowlist.empty())
                        .externalDocumentSource(documentSource)
                        .workCoordinator(scopedWorkCoordinator)
                        .maxInitialLeaseDuration(Duration.ofMinutes(5))
                        .cursorConsumer(progressCursor::set)
                        .cancellationTriggerConsumer(r -> {})
                        .build();

                    var status = runner.migrateOneShard(testContext::createReindexContext);
                    if (status == CompletionStatus.NOTHING_DONE) break;
                    shardsProcessed++;
                }

                // 2 collections × 1 shard each = exactly 2 migrateOneShard calls
                assertThat("Should process exactly 2 shards (one per collection)", shardsProcessed, equalTo(2));
            }

            verifyDocCount(target, "movies", 5);
            verifyDocCount(target, "books", 3);
        }
    }

    // --- Helpers ---

    private static SolrClusterContainer createSolr(SolrClusterContainer.SolrVersion version, boolean cloudMode) {
        return cloudMode ? SolrClusterContainer.cloud(version) : new SolrClusterContainer(version);
    }

    private static void createCollection(SolrClusterContainer solr, String name) throws Exception {
        if (solr.isCloudMode()) {
            var result = solr.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=" + name + "&numShards=1&replicationFactor=1&wt=json");
            if (result.getExitCode() != 0) {
                throw new RuntimeException("Failed to create SolrCloud collection: " + result.getStderr());
            }
        } else {
            var result = solr.execInContainer("solr", "create_core", "-c", name);
            if (result.getExitCode() != 0) {
                throw new RuntimeException("Failed to create Solr core: " + result.getStderr());
            }
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

    private static void indexBookDocuments(SolrClusterContainer solr, String collection) throws Exception {
        String[][] books = {
            {"1", "Dune", "Frank Herbert"},
            {"2", "Neuromancer", "William Gibson"},
            {"3", "Foundation", "Isaac Asimov"},
        };
        for (String[] b : books) {
            String doc = String.format(
                "[{\"id\":\"%s\",\"title\":\"%s\",\"author\":\"%s\"}]",
                b[0], b[1], b[2]
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
        return createBackup(solr, collection, "migration_backup");
    }

    private Path createBackup(SolrClusterContainer solr, String collection, String backupName) throws Exception {
        var backupRoot = tempDir.toPath().resolve("solr_backup");
        if (solr.isCloudMode()) {
            return createCloudBackup(solr, collection, backupName, backupRoot);
        }
        return createStandaloneBackup(solr, collection, backupName, backupRoot);
    }

    private Path createStandaloneBackup(
        SolrClusterContainer solr, String collection, String backupName, Path backupRoot
    ) throws Exception {
        solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + collection
                + "/replication?command=backup&location=/var/solr/data&name=" + backupName);
        waitForStandaloneBackup(solr, collection, 30);

        var snapshotDir = "/var/solr/data/snapshot." + backupName;
        var collectionDir = backupRoot.resolve(collection);
        Files.createDirectories(collectionDir);

        var listResult = solr.execInContainer("ls", snapshotDir);
        for (var fileName : listResult.getStdout().trim().split("\n")) {
            if (fileName.isEmpty()) continue;
            solr.copyFileFromContainer(
                snapshotDir + "/" + fileName,
                collectionDir.resolve(fileName).toString()
            );
        }
        return backupRoot;
    }

    private Path createCloudBackup(
        SolrClusterContainer solr, String collection, String backupName, Path backupRoot
    ) throws Exception {
        var backupLocation = "/var/solr/data/backups";
        solr.execInContainer("mkdir", "-p", backupLocation);

        solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + backupName + "&collection=" + collection
                + "&location=" + backupLocation + "&wt=json");

        // Collections API BACKUP is synchronous (no async ID used here), but wait for files
        Thread.sleep(2000);

        var containerBackupDir = backupLocation + "/" + backupName + "/" + collection;
        var collectionDir = backupRoot.resolve(collection);
        copyDirectoryFromContainer(solr, containerBackupDir, collectionDir);
        return backupRoot;
    }

    private static void copyDirectoryFromContainer(
        SolrClusterContainer solr, String containerDir, Path localDir
    ) throws Exception {
        Files.createDirectories(localDir);
        var findResult = solr.execInContainer("find", containerDir, "-type", "f");
        for (var line : findResult.getStdout().trim().split("\n")) {
            if (line.isEmpty()) continue;
            var relativePath = line.substring(containerDir.length());
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            var localFile = localDir.resolve(relativePath);
            Files.createDirectories(localFile.getParent());
            solr.copyFileFromContainer(line, localFile.toString());
        }
    }

    private void waitForStandaloneBackup(SolrClusterContainer solr, String collection, int maxSeconds)
        throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            var result = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/" + collection + "/replication?command=details&wt=json");
            if (result.getStdout().contains("success")) return;
            Thread.sleep(1000);
        }
        throw new RuntimeException("Backup did not complete within " + maxSeconds + "s");
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
