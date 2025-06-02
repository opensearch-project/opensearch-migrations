package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.lifecycle.Startables;


@Tag("isolatedTest")
public class TemporaryTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    @Test
    public void testOS2xToOS2xSimpleDocMigration() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

//        var os3xVersions = SupportedClusters.supportedSourcesOrTargets(false).stream()
//                .filter(v -> VersionMatchers.isOS_3_X.test(v.getVersion()))
//                .toList();
//        if (os3xVersions.isEmpty()) {
//            throw new IllegalStateException("No OS 3.x clusters found in supportedSourcesOrTargets()");
//        }
//        var version = os3xVersions.get(0);  // use first match
//        var os3xToOs3xPair = new SupportedClusters.MigrationPair(version, version);

        var es8ToOs2xPair = SupportedClusters.supportedPairs(false).stream()
                .filter(pair -> VersionMatchers.isOS_2_X.test(pair.source().getVersion())
                        && VersionMatchers.isOS_2_X.test(pair.target().getVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No ES 8.x to OS 3.x pair found"));

        try (
            var sourceCluster = new SearchClusterContainer(es8ToOs2xPair.source());
            var targetCluster = new SearchClusterContainer(es8ToOs2xPair.target());
        ) {
            Startables.deepStart(sourceCluster, targetCluster).join();

            var indexName = "simple_index";
            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            String body = "{\n" +
                    "  \"settings\": {\n" +
                    "    \"number_of_shards\": 1,\n" +
                    "    \"number_of_replicas\": 0,\n" +
                    "    \"refresh_interval\": -1,\n" +
                    "    \"codec\": \"zstd\"\n" +
                    "  }\n" +
                    "}";

            // Create index on source
            sourceOps.createIndex(indexName, body);

            // Print the "codec" index setting for verification
            var sourceSettingsEntry = sourceOps.get("/" + indexName + "/_settings");
            JsonNode sourceSettings = mapper.readTree(sourceSettingsEntry.getValue());

            // Try to get codec value, or log if it's missing
            JsonNode codecNode = sourceSettings.path(indexName).path("settings").path("index").path("codec");
            if (codecNode.isMissingNode() || codecNode.isNull()) {
                System.out.println("Codec: (missing during index creation)");
            } else {
                System.out.println("Codec: " + codecNode.asText());
            }

            // Always print full index settings
            System.out.println("Full index settings:");
            System.out.println(sourceSettings.toPrettyString());

            // Create index on target
//            targetOps.createIndex(indexName, body);

            // Ingest one simple document
            String simpleDoc = "{\"message\": \"Hello OpenSearch!\"}";
            sourceOps.createDocument(indexName, "doc1", simpleDoc);

            // Wait for green status on Index
            sourceOps.waitForGreenStatus(indexName);

            // Refresh so it's visible in snapshot
            sourceOps.post("/" + indexName + "/_refresh", null);

            // Take snapshot
            var snapshotName = "simple_snap";
            var snapshotRepoName = "simple_repo";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                    .host(sourceCluster.getUrl())
                    .insecure(true)
                    .build().toConnectionContext());
            var sourceClient = sourceClientFactory.determineVersionAndCreate();

            var snapshotCreator = new FileSystemSnapshotCreator(
                    snapshotName,
                    snapshotRepoName,
                    sourceClient,
                    SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                    List.of(),
                    SnapshotTestContext.factory().noOtelTracking().createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);

            sourceCluster.copySnapshotData(localDirectory.toString());
            var sourceRepo = new FileSystemRepo(localDirectory.toPath());

            // Run document migration
            var runCounter = new AtomicInteger();
            var expectedTerminationException = waitForRfsCompletion(() -> migrateDocumentsSequentially(
                    sourceRepo,
                    snapshotName,
                    List.of(),
                    targetCluster,
                    runCounter,
                    new Random(1),
                    DocumentMigrationTestContext.factory().noOtelTracking(),
                    es8ToOs2xPair.source().getVersion(),
                    es8ToOs2xPair.target().getVersion(),
                    null
            ));

            // Confirm at least one run
            Assertions.assertEquals(2, expectedTerminationException.numRuns);

            // Print statements to verify assertion
            String docId = "doc1";
            String sourceUrl = sourceCluster.getUrl();
            String targetUrl = targetCluster.getUrl();
            String indexPath = indexName + "/_doc/" + docId;

            var sourceDocEntry = sourceOps.get("/" + indexPath);
            var targetDocEntry = targetOps.get("/" + indexPath);

            JsonNode sourceDocJson = mapper.readTree(sourceDocEntry.getValue());
            JsonNode targetDocJson = mapper.readTree(targetDocEntry.getValue());

            System.out.println("\nSource document:");
            System.out.println(sourceDocJson.toPrettyString());

            System.out.println("\nTarget document:");
            System.out.println(targetDocJson.toPrettyString());

            JsonNode sourceJson = sourceDocJson.get("_source");
            JsonNode targetJson = targetDocJson.get("_source");

            System.out.println("\nAre source and target documents equal? " + sourceJson.equals(targetJson));
        }
    }
}
