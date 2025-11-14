package org.opensearch.migrations;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.MigrateOrEvaluateArgs.MetadataCustomTransformationParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Base test class providing shared setup and utility methods for migration tests.
 */
@Slf4j
abstract class BaseMigrationTest {

    @Getter
    protected SearchClusterContainer sourceCluster;
    @Getter
    protected SearchClusterContainer targetCluster;

    protected ClusterOperations sourceOperations;
    protected ClusterOperations targetOperations;

    /**
     * Starts the source and target clusters.
     */
    protected void startClusters() {
        CompletableFuture.allOf(
                CompletableFuture.runAsync(sourceCluster::start),
                CompletableFuture.runAsync(targetCluster::start)
        ).join();

        sourceOperations = new ClusterOperations(sourceCluster);
        targetOperations = new ClusterOperations(targetCluster);
    }

    /**
     * Takes a snapshot of the source cluster
     * @param sourceContainer Source container to take snapshot of
     * @param snapshotName Name of the snapshot
     * @param testSnapshotContext Context for taking the snapshot
     */
    protected void createSnapshot(
            SearchClusterContainer sourceContainer,
            String snapshotName,
            SnapshotTestContext testSnapshotContext
    ) {
        createSnapshot(sourceContainer, snapshotName, testSnapshotContext, false, true);
    }

    /**
     * Takes a snapshot of the source cluster with configurable compression and global metadata settings
     * @param sourceContainer Source container to take snapshot of
     * @param snapshotName Name of the snapshot
     * @param testSnapshotContext Context for taking the snapshot
     * @param compressionEnabled Whether to enable compression for the snapshot
     * @param includeGlobalState Whether to include global metadata in the snapshot
     */
    protected void createSnapshot(
            SearchClusterContainer sourceContainer,
            String snapshotName,
            SnapshotTestContext testSnapshotContext,
            boolean compressionEnabled,
            boolean includeGlobalState
    ) {
        var args = new CreateSnapshot.Args();
        args.snapshotName = snapshotName;
        args.fileSystemRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR;
        args.sourceArgs.host = sourceContainer.getUrl();
        args.compressionEnabled = compressionEnabled;
        args.includeGlobalState = includeGlobalState;

        var snapshotCreator = new CreateSnapshot(args, testSnapshotContext.createSnapshotCreateContext());
        snapshotCreator.run();
    }

    /**
     * Prepares migration arguments for snapshot-based migrations.
     *
     * @param snapshotName Name of the snapshot.
     * @return Prepared migration arguments.
     */
    protected MigrateOrEvaluateArgs prepareSnapshotMigrationArgs(String snapshotName, String localDirPath) {
        var arguments = new MigrateOrEvaluateArgs();
        arguments.fileSystemRepoPath = localDirPath;
        arguments.snapshotName = snapshotName;
        arguments.sourceVersion = sourceCluster.getContainerVersion().getVersion();
        arguments.targetArgs.host = targetCluster.getUrl();
        return arguments;
    }

    /**
     * Executes the migration command (either migrate or evaluate).
     *
     * @param arguments Migration arguments.
     * @param command   The migration command to execute.
     * @return The result of the migration.
     */
    protected MigrationItemResult executeMigration(MigrateOrEvaluateArgs arguments, MetadataCommands command) {
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        var metadata = new MetadataMigration();

        if (MetadataCommands.MIGRATE.equals(command)) {
            return metadata.migrate(arguments).execute(metadataContext);
        } else {
            return metadata.evaluate(arguments).execute(metadataContext);
        }
    }


    protected MetadataCustomTransformationParams useTransformationResource(String resourceName) {
        return new MetadataCustomTransformationParams() {
            public String getTransformerConfig() {
                try (
                    var inputStream = ClassLoader.getSystemResourceAsStream(resourceName);
                    var scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name()))
                    {
                    return scanner.useDelimiter("\\A").next();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
