package com.rfs.worker;

import lombok.extern.slf4j.Slf4j;

import com.rfs.common.SnapshotCreator;

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
        try {
            log.info("Attempting to initiate the snapshot...");
            snapshotCreator.registerRepo();
            snapshotCreator.createSnapshot();

            log.info("Snapshot in progress...");
            waitForSnapshotToFinish(snapshotCreator);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for Snapshot to complete", e);
            throw new SnapshotCreator.SnapshotCreationFailed(snapshotCreator.getSnapshotName());
        }
    }
}
