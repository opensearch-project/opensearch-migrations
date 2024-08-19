package com.rfs.integration;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.rfs.common.ClusterVersion;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.framework.SimpleRestoreFromSnapshot;
import com.rfs.http.ClusterOperations;
import com.rfs.transformers.TransformFunctions;
import com.rfs.version_es_6_8.GlobalMetadataFactory_ES_6_8;
import com.rfs.version_es_6_8.IndexMetadataFactory_ES_6_8;
import com.rfs.version_es_6_8.SnapshotRepoProvider_ES_6_8;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import com.rfs.worker.SnapshotRunner;
import lombok.SneakyThrows;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
public class EndToEndTest {

    @TempDir
    private File localDirectory;

    protected SimpleRestoreFromSnapshot simpleRfsInstance;

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void migrateFrom_ES_v6_8(final SearchClusterContainer.Version targetVersion) throws Exception {
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            // Setup
            // Start the clusters for testing
            var bothClustersStarted = CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> sourceCluster.start()),
                CompletableFuture.runAsync(() -> targetCluster.start())
            );
            bothClustersStarted.join();

            // Setup
            var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
            var templateName = "my_template_foo";
            sourceClusterOperations.createES6LegacyTemplate(templateName, "bar*");
            var indexName = "barstool";
            // Creates a document that uses the template
            sourceClusterOperations.createDocument(indexName, "222", "{\"hi\":\"yay\"}");

            // Take a snapshot
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

            var sourceRepo = new FileSystemRepo(localDirectory.toPath());
            var targetClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                .host(targetCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());

            var repoDataProvider = new SnapshotRepoProvider_ES_6_8(sourceRepo);
            var metadataFactory = new GlobalMetadataFactory_ES_6_8(repoDataProvider);
            var metadataCreator = new GlobalMetadataCreator_OS_2_11(
                targetClient,
                null,
                null,
                null,
                metadataContext.createMetadataMigrationContext()
            );
            var transformer = TransformFunctions.getTransformer(ClusterVersion.ES_6_8, ClusterVersion.OS_2_11, 1);
            // Action
            // Migrate metadata
            new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

            // Validation
            var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
            var res = targetClusterOperations.get("/_template/" + templateName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
            // Be sure that the mapping type on the template is an object
            assertThat(res.getValue(), Matchers.containsString("mappings\":{"));

            res = targetClusterOperations.get("/" + indexName);
            assertThat("Shouldn't exist yet, body:\n" + res.getValue(), res.getKey(), equalTo(404));

            // Action
            // Migrate indices
            var indexMetadataFactory = new IndexMetadataFactory_ES_6_8(repoDataProvider);
            var indexCreator = new IndexCreator_OS_2_11(targetClient);
            new IndexRunner(
                snapshotName,
                indexMetadataFactory,
                indexCreator,
                transformer,
                List.of(),
                metadataContext.createIndexContext()
            ).migrateIndices();

            res = targetClusterOperations.get("/barstool");
            assertThat(res.getValue(), res.getKey(), equalTo(200));

            // Action
            // PSEUDOMigrate documents
            // PSEUDO: Verify creation of 2 index templates on the cluster
            // PSEUDO: Verify creation of 5 indices on the cluster
            // - logs-01-2345
            // - logs-12-3456
            // - data-rolling
            // - playground
            // - playground2
            // PSEUDO: Verify documents

            // PSEUDO: Additional validation:
            if (SearchClusterContainer.OS_V2_14_0.equals(targetVersion)) {
                // - Mapping type parameter is removed
                // https://opensearch.org/docs/latest/breaking-changes/#remove-mapping-types-parameter
            }
        }
    }

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void migrateFrom_ES_v7_10(final SearchClusterContainer.Version targetVersion) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            migrateFrom_ES_v7_X(sourceCluster, targetCluster);
        }
    }

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void migrateFrom_ES_v7_17(final SearchClusterContainer.Version targetVersion) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_17);
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

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();

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
        var indexName = "blog_2023";

        // Creates a document that uses the template
        sourceClusterOperations.createDocument(indexName, "222", "{\"author\":\"Tobias Funke\"}");

        // ACTION: Take a snapshot
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

        var sourceRepo = new FileSystemRepo(localDirectory.toPath());
        var targetClient = new OpenSearchClient(ConnectionContextTestParams.builder()
            .host(targetCluster.getUrl())
            .insecure(true)
            .build()
            .toConnectionContext());
        // ACTION: Migrate the templates
        var repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
        var metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
        var metadataCreator = new GlobalMetadataCreator_OS_2_11(
            targetClient,
            List.of(),
            List.of(compoTemplateName),
            List.of(indexTemplateName),
            metadataContext.createMetadataMigrationContext()
        );
        var transformer = TransformFunctions.getTransformer(ClusterVersion.ES_7_10, ClusterVersion.OS_2_11, 1);
        new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();

        // Check that the templates were migrated
        var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
        var res = targetClusterOperations.get("/_index_template/" + indexTemplateName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        assertThat(res.getValue(), Matchers.containsString("composed_of\":[\"" + compoTemplateName + "\"]"));

        // ACTION: Migrate the indices
        res = targetClusterOperations.get("/" + indexName);
        assertThat("Shouldn't exist yet, body:\n" + res.getValue(), res.getKey(), equalTo(404));

        var indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
        var indexCreator = new IndexCreator_OS_2_11(targetClient);
        new IndexRunner(
            snapshotName,
            indexMetadataFactory,
            indexCreator,
            transformer,
            List.of(),
            metadataContext.createIndexContext()
        ).migrateIndices();

        // Check that the index was migrated
        res = targetClusterOperations.get("/" + indexName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));

        // Action
        // PSEUDO Migrate documents
        // PSEUDO: Verify creation of 2 index templates on the cluster
        // PSEUDO: Verify creation of 5 indices on the cluster
        // - logs-01-2345
        // - logs-12-3456
        // - data-rolling
        // - playground
        // - playground2
        // PSEUDO: Verify documents

        // PSEUDO: Additional validation:
        if (SearchClusterContainer.OS_V2_14_0.equals(targetCluster.getVersion())) {
            // - Mapping type parameter is removed
            // https://opensearch.org/docs/latest/breaking-changes/#remove-mapping-types-parameter
        }
    }
}
