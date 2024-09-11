package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.http.ClusterOperations;
import com.rfs.models.DataFilterArgs;
import com.rfs.worker.SnapshotRunner;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
@Tag("isolatedTest")
@Slf4j
class EndToEndTest {

    @TempDir
    private File localDirectory;

    @ParameterizedTest(name = "Medium of transfer {0}")
    @EnumSource(TransferMedium.class)
    void metadataMigrateFrom_ES_v6_8(TransferMedium medium) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium);
        }
    }

    @ParameterizedTest(name = "Medium of transfer {0}")
    @EnumSource(TransferMedium.class)
    void metadataMigrateFrom_ES_v7_17(TransferMedium medium) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_17);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium);
        }
    }

    @ParameterizedTest(name = "Medium of transfer {0}")
    @EnumSource(TransferMedium.class)
    void metadataMigrateFrom_ES_v7_10(TransferMedium medium) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium);
        }
    }

    @ParameterizedTest(name = "Medium of transfer {0}")
    @EnumSource(TransferMedium.class)
    void metadataMigrateFrom_OS_v1_3(TransferMedium medium) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.OS_V1_3_16);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium);
        }
    }

    private enum TransferMedium {
        SnapshotImage,
        Http
    }

    @SneakyThrows
    private void migrateFrom_ES(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster,
        final TransferMedium medium
    ) {
        // ACTION: Set up the source/target clusters
        var bothClustersStarted = CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> sourceCluster.start()),
            CompletableFuture.runAsync(() -> targetCluster.start())
        );
        bothClustersStarted.join();

        Version sourceVersion = sourceCluster.getContainerVersion().getVersion();
        var sourceIsES6_8 = VersionMatchers.isES_6_8.test(sourceVersion);
        var sourceIsES7_X = VersionMatchers.isES_7_X.test(sourceVersion) || VersionMatchers.isOS_1_X.test(sourceVersion);

        if (!(sourceIsES6_8 || sourceIsES7_X)) {
            throw new RuntimeException("This test cannot handle the source cluster version" + sourceVersion);
        }

        // Create the component and index templates
        var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
        var compoTemplateName = "simple_component_template";
        var indexTemplateName = "simple_index_template";
        if (sourceIsES7_X) {
            sourceClusterOperations.createES7Templates(compoTemplateName, indexTemplateName, "author", "blog*");
        } else if (sourceIsES6_8) {
            sourceClusterOperations.createES6LegacyTemplate(indexTemplateName, "movies*");
        }

        // Creates a document that uses the template
        var blogIndexName = "blog_2023";
        sourceClusterOperations.createDocument(blogIndexName, "222", "{\"author\":\"Tobias Funke\"}");
        var movieIndexName = "movies_2023";
        sourceClusterOperations.createDocument(movieIndexName,"123", "{\"title\":\"This is spinal tap\"}");

        var arguments = new MetadataArgs();

        switch (medium) {
            case SnapshotImage:
                var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
                var snapshotName = "my_snap";
                log.info("Source cluster {}", sourceCluster.getUrl());
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
                arguments.fileSystemRepoPath = localDirectory.getAbsolutePath();
                arguments.snapshotName = snapshotName;
                arguments.sourceVersion = sourceVersion;
                break;
        
            case Http:
                arguments.sourceArgs.host = sourceCluster.getUrl();
                break;
        }

        arguments.targetArgs.host = targetCluster.getUrl();

        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of(blogIndexName, movieIndexName);
        dataFilterArgs.componentTemplateAllowlist = List.of(compoTemplateName);
        dataFilterArgs.indexTemplateAllowlist = List.of(indexTemplateName);
        arguments.dataFilterArgs = dataFilterArgs;


        // ACTION: Migrate the templates
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        var result = new MetadataMigration(arguments).migrate().execute(metadataContext);

        log.info(result.toString());
        assertThat(result.getExitCode(), equalTo(0));

        // Check that the index was migrated
        var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
        var res = targetClusterOperations.get("/" + blogIndexName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));

        res = targetClusterOperations.get("/" + movieIndexName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        
        // Check that the templates were migrated
        if (sourceIsES7_X) {
            res = targetClusterOperations.get("/_index_template/" + indexTemplateName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
            assertThat(res.getValue(), Matchers.containsString("composed_of\":[\"" + compoTemplateName + "\"]"));
        } else if (sourceIsES6_8) {
            res = targetClusterOperations.get("/_template/" + indexTemplateName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
        }
    }
}
