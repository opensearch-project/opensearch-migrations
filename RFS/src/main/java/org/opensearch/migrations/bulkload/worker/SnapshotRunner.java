package org.opensearch.migrations.bulkload.worker;

import org.opensearch.migrations.bulkload.common.SnapshotCreator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnapshotRunner {
    private SnapshotRunner() {}

    protected static void waitForSnapshotToFinish(SnapshotCreator snapshotCreator) throws InterruptedException {
        while (!snapshotCreator.isSnapshotFinished()) {
            var waitPeriodMs = 1000;
            log.info("Snapshot not finished yet; sleeping for " + waitPeriodMs + "ms...");
            Thread.sleep(waitPeriodMs);
        }
    }

    public static void runAndWaitForCompletion(SnapshotCreator snapshotCreator) {
        runAndWaitForCompletion(snapshotCreator, false);
    }

    public static void runAndWaitForCompletion(SnapshotCreator snapshotCreator, boolean skipRepoRegistration) {
        try {
            run(snapshotCreator, skipRepoRegistration);
            waitForSnapshotToFinish(snapshotCreator);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for Snapshot to complete", e);
            throw new SnapshotCreator.SnapshotCreationFailed(snapshotCreator.getSnapshotName());
        }
    }

    public static void run(SnapshotCreator snapshotCreator) {
        run(snapshotCreator, false);
    }

    public static void run(SnapshotCreator snapshotCreator, boolean skipRepoRegistration) {
        log.info("Attempting to initiate the snapshot...");
        if (!skipRepoRegistration) {
            snapshotCreator.registerRepo();
        } else {
            log.info("Skipping snapshot repo registration as requested");
        }
        snapshotCreator.createSnapshot();
        log.info("Snapshot in progress...");
    }

}
