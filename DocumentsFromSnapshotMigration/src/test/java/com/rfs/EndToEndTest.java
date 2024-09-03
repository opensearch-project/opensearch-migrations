package com.rfs;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.rfs.common.FileSystemRepo;
import com.rfs.common.FileSystemSnapshotCreator;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.SearchClusterContainer;
import com.rfs.http.ClusterOperations;
import com.rfs.worker.DocumentsRunner;
import com.rfs.worker.SnapshotRunner;
import lombok.SneakyThrows;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class EndToEndTest extends SourceTestBase {
    @TempDir
    private File localDirectory;

    @ParameterizedTest(name = "Target OpenSearch {0}")
    @ArgumentsSource(SupportedTargetCluster.class)
    public void migrateFrom_ES_v6_8(final SearchClusterContainer.Version targetVersion) throws Exception {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        final var workCoordinationContext = WorkCoordinationTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory(workCoordinationContext).noOtelTracking();

        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            // === ACTION: Set up the source/target clusters ===
            var bothClustersStarted = CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> sourceCluster.start()),
                CompletableFuture.runAsync(() -> targetCluster.start())
            );
            bothClustersStarted.join();

            // Create a template
            var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
            var templateName = "my_template_foo";
            sourceClusterOperations.createES6LegacyTemplate(templateName, "bar*");
            var indexName = "barstool";

            // Create a document that uses the template
            sourceClusterOperations.createDocument(indexName, "222", "{\"hi\":\"yay\"}");

            // === ACTION: Take a snapshot ===
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

            // === ACTION: Migrate the templates and indices ===
            migrateMetadata(
                sourceRepo,
                targetClient,
                snapshotName,
                List.of(templateName),
                List.of(),
                List.of(),
                List.of(),
                metadataContext,
                sourceCluster.getVersion().getSourceVersion()
            );

            // Check that the templates were migrated
            var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
            var res = targetClusterOperations.get("/_template/" + templateName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
            assertThat(res.getValue(), Matchers.containsString("mappings\":{"));

            // === ACTION: Migrate the documents ===
            final var clockJitter = new Random(1);
            var result = migrateDocumentsWithOneWorker(
                sourceRepo,
                snapshotName,
                List.of(),
                targetCluster.getUrl(),
                clockJitter,
                testDocMigrationContext,
                sourceCluster.getVersion().getSourceVersion()
            );
            assertThat(result, equalTo(DocumentsRunner.CompletionStatus.WORK_COMPLETED));

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
        } finally {
            deleteTree(localDirectory.toPath());
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
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        final var workCoordinationContext = WorkCoordinationTestContext.factory().noOtelTracking();
        final var testDocMigrationContext = DocumentMigrationTestContext.factory(workCoordinationContext).noOtelTracking();

        try {

            // === ACTION: Set up the source/target clusters ===
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

            // === ACTION: Take a snapshot ===
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

            // === ACTION: Migrate the templates and indices ===
            migrateMetadata(
                sourceRepo,
                targetClient,
                snapshotName,
                List.of(),
                List.of(compoTemplateName),
                List.of(indexTemplateName),
                List.of(),
                metadataContext,
                sourceCluster.getVersion().getSourceVersion()
            );

            // Check that the templates were migrated
            var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
            var res = targetClusterOperations.get("/_index_template/" + indexTemplateName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
            assertThat(res.getValue(), Matchers.containsString("composed_of\":[\"" + compoTemplateName + "\"]"));

            // === ACTION: Migrate the documents ===
            final var clockJitter = new Random(1);
            var result = migrateDocumentsWithOneWorker(
                sourceRepo,
                snapshotName,
                List.of(),
                targetCluster.getUrl(),
                clockJitter,
                testDocMigrationContext,
                sourceCluster.getVersion().getSourceVersion()
            );
            assertThat(result, equalTo(DocumentsRunner.CompletionStatus.WORK_COMPLETED));

            // Check that the docs were migrated
            checkClusterMigrationOnFinished(sourceCluster, targetCluster, testDocMigrationContext);
        } finally {
            deleteTree(localDirectory.toPath());
        }
    }

}
