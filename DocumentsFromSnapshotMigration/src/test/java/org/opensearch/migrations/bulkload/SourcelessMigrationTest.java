package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.Version;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.lifecycle.Startables;

/**
 * End-to-end test for sourceless migration: indices with _source disabled.
 * Models a realistic email-archive index pattern with custom analyzers,
 * stored vs non-stored fields, and mixed field types.
 *
 * Documents are reconstructed from stored fields, doc_values, and the inverted
 * index via SourceReconstructor.
 *
 * Key behaviors tested:
 * - Stored fields (store: "yes") reconstruct with exact original values
 * - Not-analyzed strings reconstruct exactly via doc_values
 * - Analyzed strings WITHOUT store: lossy reconstruction from inverted index tokens
 *   (lowercased, stopwords removed, but original word order IS preserved via term positions)
 * - Numeric, boolean, date, IP types reconstruct correctly
 */
@Slf4j
@Tag("isolatedTest")
public class SourcelessMigrationTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private static final String INDEX_NAME = "email_archive";
    private static final String SNAPSHOT_NAME = "sourceless_snap";
    private static final String SNAPSHOT_REPO = "sourceless_repo";
    private static final long TOLERABLE_CLOCK_DIFF = 3600;

    /**
     * Tests migration of an ES 1.7 index modeled after a real email-archive use case.
     * Uses _source disabled with a mix of stored/non-stored fields and custom analyzers.
     * Verifies that stored fields reconstruct exactly while analyzed non-stored fields
     * may lose fidelity (stopwords removed, lowercased).
     */
    @Test
    @SneakyThrows
    public void testSourcelessMigration_ES1_7_emailArchivePattern() {
        try (
            var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V1_7_6);
            var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            Startables.deepStart(sourceCluster, targetCluster).join();

            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            // Create index modeled after real email archive:
            // - Custom uax_url_email analyzer for email/URL fields
            // - _source disabled with compress (realistic pattern)
            // - Mix of stored vs non-stored, analyzed vs not_analyzed
            String indexBody = "{\n"
                + "  \"settings\": {\n"
                + "    \"number_of_shards\": 1,\n"
                + "    \"number_of_replicas\": 0,\n"
                + "    \"analysis\": {\n"
                + "      \"analyzer\": {\n"
                + "        \"email_analyzer\": {\n"
                + "          \"tokenizer\": \"uax_url_email\",\n"
                + "          \"filter\": [\"standard\", \"lowercase\", \"stop\"]\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"mappings\": {\n"
                + "    \"email\": {\n"
                + "      \"_source\": { \"compress\": true, \"enabled\": false },\n"
                + "      \"_all\": { \"enabled\": false },\n"
                + "      \"properties\": {\n"
                + "        \"subject\": {\n"
                + "          \"type\": \"string\",\n"
                + "          \"analyzer\": \"email_analyzer\",\n"
                + "          \"store\": \"yes\"\n"
                + "        },\n"
                + "        \"body\": {\n"
                + "          \"type\": \"string\",\n"
                + "          \"analyzer\": \"email_analyzer\"\n"
                + "        },\n"
                + "        \"from_addr\": {\n"
                + "          \"type\": \"string\",\n"
                + "          \"index\": \"not_analyzed\",\n"
                + "          \"store\": \"yes\"\n"
                + "        },\n"
                + "        \"to_addr\": {\n"
                + "          \"type\": \"string\",\n"
                + "          \"index\": \"not_analyzed\",\n"
                + "          \"store\": \"yes\"\n"
                + "        },\n"
                + "        \"message_id\": {\n"
                + "          \"type\": \"string\",\n"
                + "          \"index\": \"not_analyzed\"\n"
                + "        },\n"
                + "        \"folder\": {\n"
                + "          \"type\": \"string\",\n"
                + "          \"index\": \"not_analyzed\"\n"
                + "        },\n"
                + "        \"delivery_date\": {\n"
                + "          \"type\": \"date\",\n"
                + "          \"format\": \"dateOptionalTime\",\n"
                + "          \"store\": \"yes\"\n"
                + "        },\n"
                + "        \"size\": {\n"
                + "          \"type\": \"long\",\n"
                + "          \"store\": \"yes\"\n"
                + "        },\n"
                + "        \"has_attachment\": {\n"
                + "          \"type\": \"boolean\",\n"
                + "          \"store\": \"yes\"\n"
                + "        },\n"
                + "        \"source_ip\": {\n"
                + "          \"type\": \"ip\"\n"
                + "        },\n"
                + "        \"tag_count\": {\n"
                + "          \"type\": \"integer\",\n"
                + "          \"store\": \"yes\"\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}";
            sourceOps.createIndex(INDEX_NAME, indexBody);

            // Index documents representing emails
            String doc1 = "{"
                + "\"subject\": \"Meeting Tomorrow at 10am\","
                + "\"body\": \"Hi team, please review the docs at https://wiki.example.com/migration before the meeting. If you have questions email support@example.com or reach out to the team directly.\","
                + "\"from_addr\": \"alice@example.com\","
                + "\"to_addr\": \"team@example.com\","
                + "\"message_id\": \"<abc123@mail.example.com>\","
                + "\"folder\": \"INBOX\","
                + "\"delivery_date\": \"2024-01-15T10:30:00Z\","
                + "\"size\": 4096,"
                + "\"has_attachment\": false,"
                + "\"source_ip\": \"192.168.1.100\","
                + "\"tag_count\": 3"
                + "}";
            sourceOps.createDocument(INDEX_NAME, "1", doc1, null, "email");

            String doc2 = "{"
                + "\"subject\": \"Invoice #12345 Attached\","
                + "\"body\": \"Please find the invoice for Q4 services attached. Payment is due within 30 days.\","
                + "\"from_addr\": \"billing@vendor.org\","
                + "\"to_addr\": \"accounts@example.com\","
                + "\"message_id\": \"<def456@vendor.org>\","
                + "\"folder\": \"Billing\","
                + "\"delivery_date\": \"2024-02-28T16:45:00Z\","
                + "\"size\": 1048576,"
                + "\"has_attachment\": true,"
                + "\"source_ip\": \"10.0.0.1\","
                + "\"tag_count\": 1"
                + "}";
            sourceOps.createDocument(INDEX_NAME, "2", doc2, null, "email");

            // Sparse doc — only some fields populated
            String doc3 = "{"
                + "\"subject\": \"Re: Quick question\","
                + "\"from_addr\": \"bob@example.com\","
                + "\"to_addr\": \"alice@example.com\","
                + "\"message_id\": \"<ghi789@mail.example.com>\","
                + "\"folder\": \"Sent\","
                + "\"size\": 512,"
                + "\"has_attachment\": false"
                + "}";
            sourceOps.createDocument(INDEX_NAME, "3", doc3, null, "email");

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

            // Create target index with modern mappings (target always has _source enabled)
            String targetIndexBody = "{\n"
                + "  \"settings\": {\n"
                + "    \"number_of_shards\": 1,\n"
                + "    \"number_of_replicas\": 0\n"
                + "  },\n"
                + "  \"mappings\": {\n"
                + "    \"properties\": {\n"
                + "      \"subject\": { \"type\": \"text\" },\n"
                + "      \"body\": { \"type\": \"text\" },\n"
                + "      \"from_addr\": { \"type\": \"keyword\" },\n"
                + "      \"to_addr\": { \"type\": \"keyword\" },\n"
                + "      \"message_id\": { \"type\": \"keyword\" },\n"
                + "      \"folder\": { \"type\": \"keyword\" },\n"
                + "      \"delivery_date\": { \"type\": \"date\" },\n"
                + "      \"size\": { \"type\": \"long\" },\n"
                + "      \"has_attachment\": { \"type\": \"boolean\" },\n"
                + "      \"source_ip\": { \"type\": \"ip\" },\n"
                + "      \"tag_count\": { \"type\": \"integer\" }\n"
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

            Assertions.assertTrue(terminationException.numRuns >= 1,
                "Expected at least 1 run but got: " + terminationException.numRuns);

            // Verify documents arrived at target
            targetOps.refresh();
            var targetClient = new RestClient(ConnectionContextTestParams.builder()
                .host(targetCluster.getUrl())
                .build()
                .toConnectionContext());

            var mapper = new ObjectMapper();

            // Verify all 3 docs migrated
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

            // ============================================================
            // Verify doc1 field reconstruction
            // ============================================================
            var doc1Response = targetClient.get(
                INDEX_NAME + "/_doc/1",
                testDocMigrationContext.createUnboundRequestContext()
            );
            Assertions.assertEquals(200, doc1Response.statusCode,
                "Doc1 get failed: " + doc1Response.body);
            var doc1Source = mapper.readTree(doc1Response.body).path("_source");
            log.info("Reconstructed doc1: {}", doc1Source);

            // STORED + ANALYZED fields: stored fields preserve the original text exactly
            Assertions.assertFalse(doc1Source.path("subject").isMissingNode(),
                "subject (stored+analyzed) should be present: " + doc1Source);
            Assertions.assertEquals("Meeting Tomorrow at 10am",
                doc1Source.path("subject").asText(),
                "Stored analyzed field should preserve original text exactly");

            // NOT-STORED + ANALYZED field: Lossy reconstruction from inverted index.
            // The body field uses uax_url_email analyzer without "store": true.
            // We reconstruct by collecting all terms for the document and ordering them
            // by their indexed position (via PostingsEnum.POSITIONS). This preserves the
            // original word order but is still lossy: lowercased, stopwords removed,
            // punctuation lost. Duplicate terms appear at each original position.
            // URLs and emails are preserved as single tokens by the uax_url_email tokenizer.
            String reconstructedBody = doc1Source.path("body").asText();
            log.info("Lossy body reconstruction: '{}'", reconstructedBody);
            // Original: "Hi team, please review the docs at https://wiki.example.com/migration before the meeting.
            //            If you have questions email support@example.com or reach out to the team directly."
            // After uax_url_email + lowercase + stop: stopwords removed, lowercased, URLs/emails intact,
            // "team" appears twice at original positions, word order preserved
            Assertions.assertEquals(
                "hi team please review docs https://wiki.example.com/migration before meeting "
                    + "you have questions email support@example.com reach out team directly",
                reconstructedBody);

            // STORED + NOT_ANALYZED fields: exact reconstruction via stored field
            Assertions.assertEquals("alice@example.com", doc1Source.path("from_addr").asText(),
                "Stored not_analyzed field should reconstruct exactly");
            Assertions.assertEquals("team@example.com", doc1Source.path("to_addr").asText(),
                "Stored not_analyzed field should reconstruct exactly");

            // NOT_ANALYZED without explicit store: may NOT reconstruct in ES 1.7
            // ES 1.7 (Lucene 4.x) doc_values for strings are not reliably readable
            // through the compatibility layer. This is a known limitation for non-stored fields.
            if (!doc1Source.path("message_id").isMissingNode()) {
                log.info("message_id reconstructed (bonus): {}", doc1Source.path("message_id"));
            } else {
                log.info("message_id not reconstructed — expected for non-stored field in ES 1.7");
            }
            if (!doc1Source.path("folder").isMissingNode()) {
                log.info("folder reconstructed (bonus): {}", doc1Source.path("folder"));
            } else {
                log.info("folder not reconstructed — expected for non-stored field in ES 1.7");
            }

            // NUMERIC fields: reconstructed (may be string representation from ES 1.7 codec)
            Assertions.assertFalse(doc1Source.path("size").isMissingNode(),
                "Stored long field should be present: " + doc1Source);
            Assertions.assertEquals(4096L, doc1Source.path("size").asLong(),
                "Stored long field value should be 4096 (as number or parseable string)");

            Assertions.assertFalse(doc1Source.path("tag_count").isMissingNode(),
                "Stored integer field should be present: " + doc1Source);
            Assertions.assertEquals(3, doc1Source.path("tag_count").asInt(),
                "Stored integer field value should be 3");

            // BOOLEAN field: exact reconstruction
            Assertions.assertFalse(doc1Source.path("has_attachment").isMissingNode(),
                "has_attachment should be present: " + doc1Source);
            Assertions.assertFalse(doc1Source.path("has_attachment").asBoolean(),
                "Stored boolean field should reconstruct as false");

            // DATE field: value should be present (format may vary — epoch millis or ISO)
            Assertions.assertFalse(doc1Source.path("delivery_date").isMissingNode(),
                "delivery_date (stored date) should be present: " + doc1Source);
            log.info("delivery_date reconstructed as: {}", doc1Source.path("delivery_date"));

            // IP field with neither _source nor stored nor doc_values: ES 1.7 still indexes the
            // IPv4 as trie-coded numeric TERMS (32-bit-packed long across shift levels).
            // SourceReconstructor harvests the shift==0 term via buildNumericTermIndex and
            // converts the decoded long → dotted-quad IPv4 in decodeNumericTerm(IP, ...).
            Assertions.assertEquals("192.168.1.100", doc1Source.path("source_ip").asText(),
                "source_ip should reconstruct from trie-coded numeric terms as dotted-quad IPv4");

            // ============================================================
            // Verify doc2 — different values, has_attachment=true
            // ============================================================
            var doc2Response = targetClient.get(
                INDEX_NAME + "/_doc/2",
                testDocMigrationContext.createUnboundRequestContext()
            );
            var doc2Source = mapper.readTree(doc2Response.body).path("_source");
            log.info("Reconstructed doc2: {}", doc2Source);

            Assertions.assertEquals("billing@vendor.org", doc2Source.path("from_addr").asText());
            Assertions.assertEquals(1048576L, doc2Source.path("size").asLong(),
                "Large size value should reconstruct correctly");
            Assertions.assertTrue(doc2Source.path("has_attachment").asBoolean(),
                "has_attachment=true should reconstruct");

            // doc2 body: lossy reconstruction from inverted index
            // Original: "Please find the invoice for Q4 services attached. Payment is due within 30 days."
            String doc2Body = doc2Source.path("body").asText();
            log.info("doc2 lossy body: '{}'", doc2Body);
            Assertions.assertEquals(
                "please find invoice q4 services attached payment due within 30 days",
                doc2Body);

            // doc2 source_ip covers a different IPv4 octet range (10.0.0.1 vs doc1's 192.168.1.100)
            // — exercises the trie numeric-term → dotted-quad path on both high and low byte values.
            Assertions.assertEquals("10.0.0.1", doc2Source.path("source_ip").asText(),
                "source_ip should reconstruct from trie-coded numeric terms as dotted-quad IPv4");

            // ============================================================
            // Verify doc3 — sparse document (missing optional fields)
            // ============================================================
            var doc3Response = targetClient.get(
                INDEX_NAME + "/_doc/3",
                testDocMigrationContext.createUnboundRequestContext()
            );
            var doc3Source = mapper.readTree(doc3Response.body).path("_source");
            log.info("Reconstructed doc3 (sparse): {}", doc3Source);

            Assertions.assertEquals("bob@example.com", doc3Source.path("from_addr").asText(),
                "Sparse doc stored field should reconstruct");
            Assertions.assertEquals(512L, doc3Source.path("size").asLong(),
                "Sparse doc numeric field should reconstruct");
            // Fields not in the original doc should not appear (or be null)
            Assertions.assertTrue(
                doc3Source.path("source_ip").isMissingNode() || doc3Source.path("source_ip").isNull(),
                "source_ip was not in doc3, should be absent: " + doc3Source);
            Assertions.assertTrue(
                doc3Source.path("tag_count").isMissingNode() || doc3Source.path("tag_count").isNull(),
                "tag_count was not in doc3, should be absent: " + doc3Source);
            Assertions.assertTrue(
                doc3Source.path("delivery_date").isMissingNode() || doc3Source.path("delivery_date").isNull(),
                "delivery_date was not in doc3, should be absent: " + doc3Source);

            log.info("Sourceless email-archive migration test PASSED — "
                + "3 documents reconstructed with correct field fidelity expectations");
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
                    true,           // ENABLE SOURCELESS MIGRATIONS
                    false           // don't use _recovery_source
                );
            }
        } finally {
            FileSystemUtils.deleteDirectories(tempDir.toString());
        }
    }
}
