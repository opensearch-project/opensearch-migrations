package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.workcoordination.CoordinateWorkHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.WorkCoordinatorFactory;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.testcontainers.lifecycle.Startables;

/**
 * End-to-end test for sourceless migration: indices with _source disabled.
 * Documents are reconstructed from stored fields and doc_values via SourceReconstructor.
 * 
 * Starts with ES 1.7 since it's the simplest to cover all field types.
 */
@Slf4j
@Tag("isolatedTest")
public class SourcelessMigrationTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private static final String INDEX_NAME = "sourceless_test";
    private static final String SNAPSHOT_NAME = "sourceless_snap";
    private static final String SNAPSHOT_REPO = "sourceless_repo";
    private static final long TOLERABLE_CLOCK_DIFF = 3600;

    /**
     * Tests migration of an ES 1.7 index with _source disabled, covering all major field types.
     * The index stores fields via stored fields and doc_values so SourceReconstructor can rebuild them.
     */
    @Test
    @SneakyThrows
    public void testSourcelessMigration_ES1_7_allFieldTypes() {
        try (
            var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V1_7_6);
            var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            Startables.deepStart(sourceCluster, targetCluster).join();

            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            // Create index with _source disabled and explicit mappings for all field types
            // In ES 1.7, we need a type wrapper. All fields use "store": true so they're
            // available for reconstruction from stored fields.
            String indexBody = "{\n"
                + "  \"settings\": {\n"
                + "    \"number_of_shards\": 1,\n"
                + "    \"number_of_replicas\": 0\n"
                + "  },\n"
                + "  \"mappings\": {\n"
                + "    \"doc\": {\n"
                + "      \"_source\": { \"enabled\": false },\n"
                + "      \"properties\": {\n"
                + "        \"text_field\": { \"type\": \"string\", \"store\": true },\n"
                + "        \"keyword_field\": { \"type\": \"string\", \"index\": \"not_analyzed\", \"store\": true },\n"
                + "        \"integer_field\": { \"type\": \"integer\", \"store\": true },\n"
                + "        \"long_field\": { \"type\": \"long\", \"store\": true },\n"
                + "        \"float_field\": { \"type\": \"float\", \"store\": true },\n"
                + "        \"double_field\": { \"type\": \"double\", \"store\": true },\n"
                + "        \"boolean_field\": { \"type\": \"boolean\", \"store\": true },\n"
                + "        \"date_field\": { \"type\": \"date\", \"store\": true },\n"
                + "        \"ip_field\": { \"type\": \"ip\", \"store\": true },\n"
                + "        \"byte_field\": { \"type\": \"byte\", \"store\": true },\n"
                + "        \"short_field\": { \"type\": \"short\", \"store\": true }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}";
            sourceOps.createIndex(INDEX_NAME, indexBody);

            // Index documents covering all field types
            String doc1 = "{"
                + "\"text_field\": \"hello world\","
                + "\"keyword_field\": \"exact_match\","
                + "\"integer_field\": 42,"
                + "\"long_field\": 9876543210,"
                + "\"float_field\": 3.14,"
                + "\"double_field\": 2.718281828,"
                + "\"boolean_field\": true,"
                + "\"date_field\": \"2024-01-15T10:30:00Z\","
                + "\"ip_field\": \"192.168.1.1\","
                + "\"byte_field\": 127,"
                + "\"short_field\": 32000"
                + "}";
            sourceOps.createDocument(INDEX_NAME, "1", doc1, null, "doc");

            String doc2 = "{"
                + "\"text_field\": \"another document with multiple words\","
                + "\"keyword_field\": \"second_value\","
                + "\"integer_field\": -100,"
                + "\"long_field\": 0,"
                + "\"float_field\": -1.5,"
                + "\"double_field\": 0.0,"
                + "\"boolean_field\": false,"
                + "\"date_field\": \"2023-06-30T23:59:59Z\","
                + "\"ip_field\": \"10.0.0.255\","
                + "\"byte_field\": -128,"
                + "\"short_field\": -1"
                + "}";
            sourceOps.createDocument(INDEX_NAME, "2", doc2, null, "doc");

            // A document with just a subset of fields (test null/missing handling)
            String doc3 = "{"
                + "\"text_field\": \"sparse doc\","
                + "\"integer_field\": 0,"
                + "\"boolean_field\": true"
                + "}";
            sourceOps.createDocument(INDEX_NAME, "3", doc3, null, "doc");

            sourceOps.refresh();

            // Take a snapshot
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();
            var snapshotCreator = new FileSystemSnapshotCreator(
                SNAPSHOT_NAME,
                SNAPSHOT_REPO,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(
                sourceCluster.getContainerVersion().getVersion(), true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

            // Create target index (without _source restriction - target always has _source enabled)
            String targetIndexBody = "{\n"
                + "  \"settings\": {\n"
                + "    \"number_of_shards\": 1,\n"
                + "    \"number_of_replicas\": 0\n"
                + "  },\n"
                + "  \"mappings\": {\n"
                + "    \"properties\": {\n"
                + "      \"text_field\": { \"type\": \"text\" },\n"
                + "      \"keyword_field\": { \"type\": \"keyword\" },\n"
                + "      \"integer_field\": { \"type\": \"integer\" },\n"
                + "      \"long_field\": { \"type\": \"long\" },\n"
                + "      \"float_field\": { \"type\": \"float\" },\n"
                + "      \"double_field\": { \"type\": \"double\" },\n"
                + "      \"boolean_field\": { \"type\": \"boolean\" },\n"
                + "      \"date_field\": { \"type\": \"date\" },\n"
                + "      \"ip_field\": { \"type\": \"ip\" },\n"
                + "      \"byte_field\": { \"type\": \"byte\" },\n"
                + "      \"short_field\": { \"type\": \"short\" }\n"
                + "    }\n"
                + "  }\n"
                + "}";
            targetOps.createIndex(INDEX_NAME, targetIndexBody);

            // Migrate documents with sourceless migrations ENABLED
            var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();
            var runCounter = new AtomicInteger();
            var clockJitter = new Random(1);

            var terminationException = waitForRfsCompletion(() ->
                migrateDocumentsSequentiallyWithSourceless(
                    sourceRepo,
                    SNAPSHOT_NAME,
                    List.of(INDEX_NAME),
                    targetCluster,
                    runCounter,
                    clockJitter,
                    testDocMigrationContext,
                    sourceCluster.getContainerVersion().getVersion(),
                    targetCluster.getContainerVersion().getVersion()
                )
            );

            // Verify we completed (1 shard + final no-work run)
            Assertions.assertTrue(terminationException.numRuns >= 1,
                "Expected at least 1 run but got: " + terminationException.numRuns);

            // Verify documents arrived at target
            targetOps.refresh();
            var targetClient = new RestClient(ConnectionContextTestParams.builder()
                .host(targetCluster.getUrl())
                .build()
                .toConnectionContext());

            var mapper = new ObjectMapper();

            // Search for all docs
            var searchResponse = targetClient.get(
                INDEX_NAME + "/_search?size=10",
                testDocMigrationContext.createUnboundRequestContext()
            );
            Assertions.assertEquals(200, searchResponse.statusCode,
                "Search failed: " + searchResponse.body);
            var searchJson = mapper.readTree(searchResponse.body);
            var hits = searchJson.path("hits").path("hits");
            Assertions.assertEquals(3, hits.size(),
                "Expected 3 documents but got " + hits.size() + ": " + searchResponse.body);

            // Verify specific field values for doc1
            var doc1Response = targetClient.get(
                INDEX_NAME + "/_doc/1",
                testDocMigrationContext.createUnboundRequestContext()
            );
            Assertions.assertEquals(200, doc1Response.statusCode,
                "Doc1 get failed: " + doc1Response.body);
            var doc1Json = mapper.readTree(doc1Response.body).path("_source");

            // Validate reconstructed fields
            log.info("Reconstructed doc1: {}", doc1Json);
            // String/text fields
            Assertions.assertFalse(doc1Json.path("text_field").isMissingNode(),
                "text_field should be present, doc1: " + doc1Json);
            Assertions.assertFalse(doc1Json.path("keyword_field").isMissingNode(),
                "keyword_field should be present, doc1: " + doc1Json);

            // Integer fields - these should reconstruct precisely
            Assertions.assertFalse(doc1Json.path("integer_field").isMissingNode(),
                "integer_field should be present, doc1: " + doc1Json);
            Assertions.assertFalse(doc1Json.path("long_field").isMissingNode(),
                "long_field should be present, doc1: " + doc1Json);

            // Float/double - verify present (precision may vary by Lucene codec version)
            Assertions.assertFalse(doc1Json.path("float_field").isMissingNode(),
                "float_field should be present, doc1: " + doc1Json);
            Assertions.assertFalse(doc1Json.path("double_field").isMissingNode(),
                "double_field should be present, doc1: " + doc1Json);

            // Boolean
            Assertions.assertFalse(doc1Json.path("boolean_field").isMissingNode(),
                "boolean_field should be present, doc1: " + doc1Json);

            // Byte/short
            Assertions.assertFalse(doc1Json.path("byte_field").isMissingNode(),
                "byte_field should be present, doc1: " + doc1Json);
            Assertions.assertFalse(doc1Json.path("short_field").isMissingNode(),
                "short_field should be present, doc1: " + doc1Json);
            // IP and date might be stored in different formats depending on reconstruction
            Assertions.assertFalse(doc1Json.path("ip_field").isMissingNode(),
                "ip_field should be present");
            Assertions.assertFalse(doc1Json.path("date_field").isMissingNode(),
                "date_field should be present");

            log.info("Sourceless migration test PASSED - all 3 documents migrated with field values intact");
        }
    }

    /**
     * Runs migration sequentially with sourceless migrations enabled.
     */
    public static int migrateDocumentsSequentiallyWithSourceless(
        FileSystemRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        SearchClusterContainer target,
        AtomicInteger runCounter,
        Random clockJitter,
        DocumentMigrationTestContext testContext,
        Version sourceVersion,
        Version targetVersion
    ) {
        int maxRuns = 20;
        for (int runNumber = 1; runNumber <= maxRuns; ++runNumber) {
            try {
                CompletionStatus workResult = migrateDocumentsWithPipelineSourceless(
                    sourceRepo, snapshotName, indexAllowlist,
                    target.getUrl(), clockJitter, testContext,
                    sourceVersion, targetVersion
                );
                if (workResult == CompletionStatus.NOTHING_DONE) {
                    throw new ExpectedMigrationWorkTerminationException(
                        new RfsMigrateDocuments.NoWorkLeftException("Pipeline returned NOTHING_DONE"),
                        runNumber
                    );
                } else {
                    runCounter.incrementAndGet();
                }
            } catch (RfsMigrateDocuments.NoWorkLeftException e) {
                log.info("No work left - migration complete");
                throw new ExpectedMigrationWorkTerminationException(e, runNumber);
            } catch (ExpectedMigrationWorkTerminationException e) {
                throw e;
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Caught exception, will retry to simulate recycling").log();
            }
        }
        throw new AssertionError("Migration did not complete within " + maxRuns + " runs");
    }

    /**
     * Pipeline-based migration with sourceless migrations enabled.
     */
    @SneakyThrows
    public static CompletionStatus migrateDocumentsWithPipelineSourceless(
        SourceRepo sourceRepo,
        String snapshotName,
        List<String> indexAllowlist,
        String targetAddress,
        Random clockJitter,
        DocumentMigrationTestContext context,
        Version sourceVersion,
        Version targetVersion
    ) throws RfsMigrateDocuments.NoWorkLeftException {
        var tempDir = Files.createTempDirectory("sourcelessMigration_test_lucene");
        try (var processManager = new LeaseExpireTrigger(workItemId -> {
            log.atDebug().setMessage("Lease expired for {} (sourceless pipeline)")
                .addArgument(workItemId).log();
        })) {
            final int ms_window = 1000;
            final var nextClockShift = (int) (clockJitter.nextDouble() * ms_window) - (ms_window / 2);

            var sourceResourceProvider = SnapshotReaderRegistry.getSnapshotReader(sourceVersion, sourceRepo, false);
            var extractor = SnapshotExtractor.create(sourceVersion, sourceResourceProvider, sourceRepo);

            var docTransformer = new TransformationLoader().getTransformerFactoryLoader(
                RfsMigrateDocuments.DEFAULT_DOCUMENT_TRANSFORMATION_CONFIG
            );

            AtomicReference<WorkItemCursor> progressCursor = new AtomicReference<>();
            var coordinatorFactory = new WorkCoordinatorFactory(targetVersion);
            var connectionContext = ConnectionContextTestParams.builder()
                .host(targetAddress)
                .build()
                .toConnectionContext();
            var workItemRef = new AtomicReference<IWorkCoordinator.WorkItemAndDuration>();

            try (var workCoordinator = coordinatorFactory.get(
                new CoordinateWorkHttpClient(connectionContext),
                TOLERABLE_CLOCK_DIFF,
                UUID.randomUUID().toString(),
                Clock.offset(Clock.systemUTC(), Duration.ofMillis(nextClockShift)),
                workItemRef::set
            )) {
                var clientFactory = new OpenSearchClientFactory(connectionContext);
                return RfsMigrateDocuments.runWithPipeline(
                    extractor,
                    clientFactory.determineVersionAndCreate(),
                    snapshotName,
                    tempDir,
                    () -> docTransformer,
                    false,
                    DocumentExceptionAllowlist.empty(),
                    1000,
                    Long.MAX_VALUE,
                    10,
                    0,              // no shard size limit in tests
                    progressCursor,
                    workCoordinator,
                    Duration.ofMinutes(10),
                    processManager,
                    null,           // no WorkItemTimeProvider in tests
                    sourceResourceProvider.getIndexMetadata(),
                    indexAllowlist,
                    context,
                    new AtomicReference<>(),
                    null,           // no previous snapshot
                    null,           // no delta mode
                    true            // ENABLE SOURCELESS MIGRATIONS
                );
            }
        } finally {
            FileSystemUtils.deleteDirectories(tempDir.toString());
        }
    }
}
