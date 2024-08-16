package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContext.TargetArgs;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.http.ClusterOperations;
import com.rfs.worker.SnapshotRunner;
import lombok.SneakyThrows;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
@Tag("longTest")
class EndToEndTest {

    @TempDir
    private File localDirectory;

    @Test
    void metadataMigrateFrom_ES_v7_10_to_OS_v2_14() throws Exception {
        final var targetVersion = SearchClusterContainer.OS_V2_14_0;
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrateFrom_ES_v7_X(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void migrateFrom_ES_v7_X(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster
    ) {
        // ACTION: Set up the source/target clusters
        var bothClustersStarted = CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> sourceCluster.start()),
            CompletableFuture.runAsync(() -> targetCluster.start())
        );
        bothClustersStarted.join();

        // Create the component and index templates
        var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
        var compoTemplateName = "simple_component_template";
        var indexTemplateName = "simple_index_template";
        sourceClusterOperations.createES7Templates(compoTemplateName, indexTemplateName, "author", "blog*");

        // Creates a document that uses the template
        var indexName = "blog_2023";
        sourceClusterOperations.createDocument(indexName, "222", "{\"author\":\"Tobias Funke\"}");

        // ACTION: Take a snapshot
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var snapshotName = "my_snap";
        var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
            .host(sourceCluster.getUrl())
            .insecure(true)
            .build()
            .toConnectionContext());
        var snapshotCreator = new FileSystemSnapshotCreator(
            snapshotName,
            sourceClient,
            SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
            snapshotContext.createSnapshotCreateContext()
        );
        SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
        sourceCluster.copySnapshotData(localDirectory.toString());

        var targetArgs = new TargetArgs();
        targetArgs.host = targetCluster.getUrl();

        var arguments = new MetadataArgs();
        arguments.fileSystemRepoPath = localDirectory.getAbsolutePath();
        arguments.snapshotName = snapshotName;
        arguments.targetArgs = targetArgs;
        arguments.indexAllowlist = List.of(indexName);
        arguments.componentTemplateAllowlist = List.of(compoTemplateName);
        arguments.indexTemplateAllowlist = List.of(indexTemplateName);

        // ACTION: Migrate the templates
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        var result = new MetadataMigration(arguments).migrate().execute(metadataContext);

        assertThat(result.getExitCode(), equalTo(0));

        // Check that the templates were migrated
        var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
        var res = targetClusterOperations.get("/_index_template/" + indexTemplateName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        assertThat(res.getValue(), Matchers.containsString("composed_of\":[\"" + compoTemplateName + "\"]"));

        // Check that the index was migrated
        res = targetClusterOperations.get("/" + indexName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));

        // PSEUDO: Additional validation:
        if (SearchClusterContainer.OS_V2_14_0.equals(targetCluster.getVersion())) {
            // - Mapping type parameter is removed
            // https://opensearch.org/docs/latest/breaking-changes/#remove-mapping-types-parameter
        }
    }
}
