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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.lifecycle.Startables;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the continuous-worker behavior for RFS:
 *  - runs with --continuous-mode (loop)
 *  - processes all available work items
 *  - remains alive (idles) when no work is left
 *  - exits cleanly when interrupted (simulated SIGTERM)
 */
@Slf4j
@Disabled("Test disabled from Gradle builds")
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
            runContinuousHappyPath(sourceCluster, targetCluster, sampleDocs(), 2);
        }
    }

    @Test
    void testContinuous_ES710_to_OS13() throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V1_3_16)
        ) {
            runContinuousHappyPath(sourceCluster, targetCluster, sampleDocs(), 2);
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
            // 1) Bring clusters up and define matching index
            Startables.deepStart(sourceCluster, targetCluster).join();
            var sourceOps = new ClusterOperations(sourceCluster);
            var targetOps = new ClusterOperations(targetCluster);

            String indexBody = "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}";
            sourceOps.createIndex(INDEX, indexBody);
            targetOps.createIndex(INDEX, indexBody);

            // 2) Seed source with docs
            for (int i = 0; i < docs.size(); i++) {
                sourceOps.createDocument(INDEX, String.valueOf(i), docs.get(i), null, null);
            }
            sourceOps.post("/" + INDEX + "/_refresh", null);

            // 3) Snapshot + local copy for RFS
            var snapshotName = "cw-snapshot-" + System.currentTimeMillis();
            createSnapshot(sourceCluster, snapshotName, snapshotContext);
            sourceCluster.copySnapshotData(localDirectory.toString());

            // 4) Kick off RFS worker (invoking main) in continuous mode
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
            CountDownLatch finished = new CountDownLatch(1);

            workerThread = new Thread(() -> {
                try {
                    started.countDown();
                    RfsMigrateDocuments.main(args);
                } catch (Throwable t) {
                    log.error("Worker threw exception", t);
                    fail("Worker threw: " + t);
                } finally {
                    finished.countDown();
                }
            }, "rfs-continuous-worker");
            workerThread.start();
            started.await();

            // 5) Wait until target sees all docs
            awaitCount(targetCluster, INDEX, docs.size(), Duration.ofSeconds(30));

            // 6) Give worker a brief grace period to enter idle state
            Thread.sleep(350);
            
            // Worker should remain alive (idling) after finishing current work
            assertTrue(workerThread.isAlive(), "Worker should be idling in continuous mode after finishing work");

            // 7) Simulate orchestrator stop (ReplicaSet scaled to 0) via interrupt
            workerThread.interrupt();
            workerThread.join(10_000);
            assertFalse(workerThread.isAlive(), "Worker should exit promptly after interruption");

            // Ensure the thread actually left main (clean termination path)
            assertTrue(finished.getCount() == 0, "Worker did not signal finished latch");
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
     * Build CLI args for RFS continuous loop.
     * We explicitly set continuous-mode and a short no-work delay so the test is snappy.
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
            "--target-host", targetCluster.getUrl(),
            "--snapshot-name", snapshotName,
            "--snapshot-local-dir", localDirectory.toString(),
            "--lucene-dir", localDirectory.toString() + "/lucene",
            "--source-version", sourceCluster.getContainerVersion().getVersion().toString(),
            "--session-name", sessionName,
            "--index-allowlist", index,
            "--no-work-retry-delay-seconds", String.valueOf(noWorkDelaySeconds)
        ));
        return args.toArray(String[]::new);
    }

    /**
     * Poll target until the index has expectedCount docs or timeout expires.
     */
    private void awaitCount(
        SearchClusterContainer targetCluster,
        String index,
        long expectedCount,
        Duration timeout
    ) throws Exception {
        var ops = new ClusterOperations(targetCluster);
        long start = System.currentTimeMillis();
        long limit = timeout.toMillis();

        while (System.currentTimeMillis() - start < limit) {
            try {
                ops.post("/" + index + "/_refresh", null);
                var resp = ops.get("/" + index + "/_count");
                if (resp.getKey() == 200) {
                    var body = resp.getValue();
                    if (body.contains("\"count\":" + expectedCount)) {
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("Count check failed; retrying...", e);
            }
            Thread.sleep(400);
        }
        fail("Timed out waiting for " + expectedCount + " docs in index " + index + " within " + timeout);
    }
}
