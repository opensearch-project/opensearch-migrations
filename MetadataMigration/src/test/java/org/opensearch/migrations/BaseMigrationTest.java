package org.opensearch.migrations;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.MigrateOrEvaluateArgs.MetadataCustomTransformationParams;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base test class providing shared setup and utility methods for migration tests.
 */
@Slf4j
abstract class BaseMigrationTest {

    @TempDir
    protected Path localDirectory;

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
     * Sets up a snapshot repository and takes a snapshot of the source cluster.
     *
     * @param snapshotName Name of the snapshot.
     * @return The name of the created snapshot.
     */
    @SneakyThrows
    protected String createSnapshot(String snapshotName) {
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
        var sourceClient = clientFactory.determineVersionAndCreate();
        var snapshotCreator = new org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator(
                snapshotName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
        );
        org.opensearch.migrations.bulkload.worker.SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
        sourceCluster.copySnapshotData(localDirectory.toString());
        return snapshotName;
    }

    /**
     * Prepares migration arguments for snapshot-based migrations.
     *
     * @param snapshotName Name of the snapshot.
     * @return Prepared migration arguments.
     */
    protected MigrateOrEvaluateArgs prepareSnapshotMigrationArgs(String snapshotName) {
        var arguments = new MigrateOrEvaluateArgs();
        arguments.fileSystemRepoPath = localDirectory.toString();
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

    /**
     * Creates an OpenSearch client for the given cluster.
     *
     * @param cluster The cluster container.
     * @return An OpenSearch client.
     */
    protected OpenSearchClient createClient(SearchClusterContainer cluster) {
        var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(cluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
        return clientFactory.determineVersionAndCreate();
    }

    protected SnapshotTestContext createSnapshotContext() {
        return SnapshotTestContext.factory().noOtelTracking();
    }

    protected FileSystemSnapshotCreator createSnapshotCreator(String snapshotName, OpenSearchClient client, SnapshotTestContext context) {
        return new FileSystemSnapshotCreator(
                snapshotName,
                client,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                context.createSnapshotCreateContext()
        );
    }

    @SneakyThrows
    protected void runSnapshotAndCopyData(FileSystemSnapshotCreator snapshotCreator, SearchClusterContainer cluster) {
        SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
        cluster.copySnapshotData(localDirectory.toString());
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
