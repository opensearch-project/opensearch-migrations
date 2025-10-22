package org.opensearch.migrations.bulkload;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.lifecycle.Startables;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Continuous worker behavior (ReplicaSet-friendly):
 * - runs with --continuous-mode (default true)
 * - processes multiple work items in sequence
 * - idles/sleeps when no work is available (does not exit)
 * - exits cleanly on interruption (simulated SIGTERM)
 *
 * This test extends SourceTestBase to reuse existing cluster management infrastructure.
 */
@Slf4j
public class ContinuousWorkerTest extends SourceTestBase {

    @TempDir
    private File localDirectory;

    private static final String INDEX = "cw-index";
    private Thread workerThread;

    @BeforeEach
    void beforeEach() {
        workerThread = null;
    }

    @AfterEach
    void afterEach() throws InterruptedException {
        if (workerThread != null && workerThread.isAlive()) {
            workerThread.interrupt();
            workerThread.join(10_000);
        }
    }

    @Test
    void testContinuous_ES56_to_OS219() throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V5_6_16);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_1)
        ) {
            runContinuousHappyPath(sourceCluster, targetCluster, sampleDocs(), 1);
        }
    }

    @Test
    void testContinuous_ES710_to_OS13() throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V1_3_16)
        ) {
            runContinuousHappyPath(sourceCluster, targetCluster, sampleDocs(), 1);
        }
    }

    private void runContinuousHappyPath(
        SearchClusterContainer sourceCluster,
        SearchClusterContainer targetCluster,
        List<String> docs,
        int noWorkDelaySeconds
    ) throws Exception {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            // 1) Index setup - start clusters and create index
            Startables.deepStart(sourceCluster, targetCluster).join();
            
            var sourceClusterOperations = new ClusterOperations(sourceCluster);
            var targetClusterOperations = new ClusterOperations(targetCluster);

            // Create index on both clusters
            String indexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}";
            sourceClusterOperations.createIndex(INDEX, indexBody);
            targetClusterOperations.createIndex(INDEX, indexBody);

            // 2) Seed source with documents
            for (int i = 0; i < docs.size(); i++) {
                sourceClusterOperations.createDocument(INDEX, String.valueOf(i), docs.get(i), null, null);
            }
            sourceClusterOperations.post("/" + INDEX + "/_refresh", null);

            // 3) Create snapshot
            var snapshotName = "cw-snapshot-" + System.currentTimeMillis();
            createSnapshot(sourceCluster, snapshotName, snapshotContext);
            sourceCluster.copySnapshotData(localDirectory.toString());

            // 4) Start RFS in continuous mode (in-process) on a background thread
            String session = "cw-" + System.currentTimeMillis();
            String[] args = buildArgsForContinuousRun(
                session,
                sourceCluster,
                targetCluster,
                snapshotName,
                INDEX,
                noWorkDelaySeconds
            );

            CountDownLatch started = new CountDownLatch(1);
            workerThread = new Thread(() -> {
                try {
                    started.countDown();
                    RfsMigrateDocuments.main(args);
                } catch (Throwable t) {
                    // Make failures surface in test logs
                    log.error("Worker threw exception", t);
                    fail("Worker threw: " + t);
                }
            }, "rfs-continuous-worker");
            workerThread.start();
            started.await();

            // 5) Wait for the docs to arrive on target
            awaitCount(targetCluster, INDEX, docs.size(), Duration.ofSeconds(30));

            // 6) Ensure the worker is still alive (continuous mode should not exit after success)
            assertTrue(workerThread.isAlive(), "Continuous worker should remain alive (idling) after finishing current work");

            // 7) Simulate orchestrator stop: interrupt the thread (loop condition checks interruption)
            workerThread.interrupt();
            workerThread.join(10_000);
            assertFalse(workerThread.isAlive(), "Worker should exit promptly after interruption");

        } finally {
            deleteTree(localDirectory.toPath());
        }
    }

    private static List<String> sampleDocs() {
        var docs = new ArrayList<String>();
        docs.add("{\"id\":1,\"city\":\"seattle\",\"pop\":744955}");
        docs.add("{\"id\":2,\"city\":\"portland\",\"pop\":652503}");
        docs.add("{\"id\":3,\"city\":\"boise\",\"pop\":236634}");
        docs.add("{\"id\":4,\"city\":\"spokane\",\"pop\":229071}");
        docs.add("{\"id\":5,\"city\":\"tacoma\",\"pop\":219205}");
        return docs;
    }

    /**
     * Build CLI args for continuous mode testing.
     */
    private String[] buildArgsForContinuousRun(
        String sessionName,
        SearchClusterContainer sourceCluster,
        SearchClusterContainer targetCluster,
        String snapshotName,
        String index,
        int noWorkDelaySeconds
    ) {
        List<String> args = new ArrayList<>(List.of(
            // target cluster
            "--target-host", targetCluster.getUrl(),

            // snapshot params
            "--snapshot-name", snapshotName,
            "--snapshot-local-dir", localDirectory.toString(),
            "--lucene-dir", localDirectory.toString() + "/lucene",

            // source version
            "--source-version", sourceCluster.getContainerVersion().getVersion().toString(),

            // session
            "--session-name", sessionName,

            // index filtering to keep the test tight
            "--index-allowlist", index,

            // continuous behavior - note: continuous-mode defaults to true, but being explicit
            "--continuous-mode",
            "--no-work-retry-delay-seconds", String.valueOf(noWorkDelaySeconds)
        ));

        return args.toArray(String[]::new);
    }

    /**
     * Helper method to wait for a specific document count on the target cluster.
     * This method polls the target cluster until the expected count is reached or timeout occurs.
     */
    private void awaitCount(SearchClusterContainer targetCluster, String index, long expectedCount, Duration timeout) throws Exception {
        var targetClusterOperations = new ClusterOperations(targetCluster);
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // Refresh the index to ensure we get the latest count
                targetClusterOperations.post("/" + index + "/_refresh", null);
                
                // Get the document count
                var response = targetClusterOperations.get("/" + index + "/_count");
                if (response.getKey() == 200) {
                    var countResponse = response.getValue();
                    // Parse the count from the response (simple string parsing for test purposes)
                    if (countResponse.contains("\"count\":" + expectedCount)) {
                        return; // Success!
                    }
                }
                
                Thread.sleep(500); // Wait 500ms before next check
            } catch (Exception e) {
                log.debug("Error checking document count, will retry", e);
                Thread.sleep(500);
            }
        }
        
        fail("Expected " + expectedCount + " documents in index " + index + " within " + timeout + ", but timeout occurred");
    }
}
