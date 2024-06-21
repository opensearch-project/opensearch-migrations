package com.rfs.worker;

import com.rfs.cms.IWorkCoordinator;
import com.rfs.cms.ScopedWorkCoordinator;
import com.rfs.common.IndexMetadata;
import com.rfs.common.SnapshotRepo;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;

/**
 * This class adds workitemes (leasable mutexes) via the WorkCoordinator so that future
 * runs of the DocumentsRunner can pick one of those items and migrate the documents for
 * that section of work.
 */
@Slf4j
public class ShardWorkPreparer {

    public static final String SHARD_SETUP_WORK_ITEM_ID = "shard_setup";

    public void run(ScopedWorkCoordinator scopedWorkCoordinator, IndexMetadata.Factory metadataFactory,
                    String snapshotName)
            throws IOException, InterruptedException {

        // ensure that there IS an index to house the shared state that we're going to be manipulating
        scopedWorkCoordinator.workCoordinator.setup();

        scopedWorkCoordinator.ensurePhaseCompletion(
                wc -> {
                    try {
                        return wc.createOrUpdateLeaseForWorkItem(SHARD_SETUP_WORK_ITEM_ID, Duration.ofMinutes(5));
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                },
                new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<Void>() {
                    @Override
                    public Void onAlreadyCompleted() throws IOException {
                        return null;
                    }

                    @Override
                    public Void onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException {
                        prepareShardWorkItems(scopedWorkCoordinator.workCoordinator, metadataFactory, snapshotName);
                        return null;
                    }

                    @Override
                    public Void onNoAvailableWorkToBeDone() throws IOException {
                        return null;
                    }
                });
    }

    @SneakyThrows
    private static void prepareShardWorkItems(IWorkCoordinator workCoordinator,
                                              IndexMetadata.Factory metadataFactory, String snapshotName) {
        log.info("Setting up the Documents Work Items...");
        SnapshotRepo.Provider repoDataProvider = metadataFactory.getRepoDataProvider();
        for (SnapshotRepo.Index index : repoDataProvider.getIndicesInSnapshot(snapshotName)) {
            IndexMetadata.Data indexMetadata = metadataFactory.fromRepo(snapshotName, index.getName());
            log.info("Index " + indexMetadata.getName() + " has " + indexMetadata.getNumberOfShards() + " shards");
            for (int shardId = 0; shardId < indexMetadata.getNumberOfShards(); shardId++) {
                log.info("Creating Documents Work Item for index: " + indexMetadata.getName() + ", shard: " + shardId);
                workCoordinator.createUnassignedWorkItem(IndexAndShard.formatAsWorkItemString(indexMetadata.getName(), shardId));
            }
        }
        log.info("Finished setting up the Documents Work Items.");
    }
}
