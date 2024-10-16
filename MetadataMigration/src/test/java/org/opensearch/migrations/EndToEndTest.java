package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster
 */
@Tag("isolatedTest")
@Slf4j
class EndToEndTest {

    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(TransferMedium.Http, MetadataCommands.EVALUATE)
            // Arguments.of(TransferMedium.SnapshotImage, MetadataCommands.MIGRATE),
            // Arguments.of(TransferMedium.Http, MetadataCommands.MIGRATE)
        );
    }

    @ParameterizedTest(name = "Command {1}, Medium of transfer {0}")
    @MethodSource(value = "scenarios")
    void metadataMigrateFrom_ES_v6_8(TransferMedium medium, MetadataCommands command) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium, command);
        }
    }

    @ParameterizedTest(name = "Command {1}, Medium of transfer {0}")
    @MethodSource(value = "scenarios")
    void metadataMigrateFrom_ES_v7_17(TransferMedium medium, MetadataCommands command) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_17);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium, command);
        }
    }

    @ParameterizedTest(name = "Command {1}, Medium of transfer {0}")
    @MethodSource(value = "scenarios")
    void metadataMigrateFrom_ES_v7_10(TransferMedium medium, MetadataCommands command) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium, command);
        }
    }

    @ParameterizedTest(name = "Command {1}, Medium of transfer {0}")
    @MethodSource(value = "scenarios")
    void metadataMigrateFrom_OS_v1_3(TransferMedium medium, MetadataCommands command) throws Exception {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.OS_V1_3_16);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {
            migrateFrom_ES(sourceCluster, targetCluster, medium, command);
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
        final TransferMedium medium,
        final MetadataCommands command
    ) {
        // ACTION: Set up the source/target clusters
        var bothClustersStarted = CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> sourceCluster.start()),
            CompletableFuture.runAsync(() -> targetCluster.start())
        );
        bothClustersStarted.join();

        Version sourceVersion = sourceCluster.getContainerVersion().getVersion();
        var sourceIsES6_8 = VersionMatchers.isES_6_X.test(sourceVersion);
        var sourceIsES7_X = VersionMatchers.isES_7_X.test(sourceVersion) || VersionMatchers.isOS_1_X.test(sourceVersion);

        if (!(sourceIsES6_8 || sourceIsES7_X)) {
            throw new RuntimeException("This test cannot handle the source cluster version" + sourceVersion);
        }

        var testData = new TestData();
        // Create the component and index templates
        var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
        if (sourceIsES7_X) {
            sourceClusterOperations.createES7Templates(testData.compoTemplateName, testData.indexTemplateName, "author", "blog*");
        } else if (sourceIsES6_8) {
            sourceClusterOperations.createES6LegacyTemplate(testData.indexTemplateName, "blog*");
        }

        // Creates a document that uses the template
        sourceClusterOperations.createDocument(testData.blogIndexName, "222", "{\"author\":\"Tobias Funke\"}");
        sourceClusterOperations.createDocument(testData.movieIndexName,"123", "{\"title\":\"This is spinal tap\"}");
        sourceClusterOperations.createDocument(testData.indexThatAlreadyExists, "doc66", "{}");

        sourceClusterOperations.createAlias(testData.aliasName, "movies*");

        var aliasName = "movies-alias";
        sourceClusterOperations.createAlias(aliasName, "movies*");

        var arguments = new MigrateOrEvaluateArgs();

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
                    List.of(),
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
        dataFilterArgs.indexAllowlist = List.of();
        dataFilterArgs.componentTemplateAllowlist = List.of(testData.compoTemplateName);
        dataFilterArgs.indexTemplateAllowlist = List.of(testData.indexTemplateName);
        arguments.dataFilterArgs = dataFilterArgs;

        var targetClusterOperations = new ClusterOperations(targetCluster.getUrl());
        targetClusterOperations.createDocument(testData.indexThatAlreadyExists, "doc77", "{}");

        // ACTION: Migrate the templates
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        var metadata = new MetadataMigration();
        
        MigrationItemResult result;
        if (MetadataCommands.MIGRATE.equals(command)) {
            result = metadata.migrate(arguments).execute(metadataContext);
        } else {
            result = metadata.evaluate(arguments).execute(metadataContext);
        }

        verifyCommandResults(result, sourceIsES6_8, testData);

        verifyTargetCluster(targetClusterOperations, command, sourceIsES6_8, testData);
    }

    private static class TestData {
        final String compoTemplateName = "simple_component_template";
        final String indexTemplateName = "simple_index_template";
        final String aliasInTemplate = "alias1";
        final String blogIndexName = "blog_2023";
        final String movieIndexName = "movies_2023";
        final String aliasName = "movies-alias";
        final String indexThatAlreadyExists = "already-exists";
    }

    private void verifyCommandResults(
        MigrationItemResult result,
        boolean sourceIsES6_8,
        TestData testData) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var migratedItems = result.getItems();
        assertThat(getNames(migratedItems.getIndexTemplates()), containsInAnyOrder(testData.indexTemplateName));
        assertThat(getNames(migratedItems.getComponentTemplates()), equalTo(sourceIsES6_8 ? List.of() : List.of(testData.compoTemplateName)));
        assertThat(getNames(migratedItems.getIndexes()), containsInAnyOrder(testData.blogIndexName, testData.movieIndexName, testData.indexThatAlreadyExists));
        assertThat(getNames(migratedItems.getAliases()), containsInAnyOrder(testData.aliasInTemplate, testData.aliasName));
    }

    private List<String> getNames(List<CreationResult> items) {
        return items.stream().map(r -> r.getName()).collect(Collectors.toList());
    }

    private void verifyTargetCluster(
        ClusterOperations targetClusterOperations,
        MetadataCommands command,
        boolean sourceIsES6_8,
        TestData testData
        ) {
        var expectUpdatesOnTarget = MetadataCommands.MIGRATE.equals(command);
        // If the command was migrate, the target cluster should have the items, if not they
        var verifyResponseCode = expectUpdatesOnTarget ? equalTo(200) : equalTo(404);

        // Check that the index was migrated
        var res = targetClusterOperations.get("/" + testData.blogIndexName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);

        res = targetClusterOperations.get("/" + testData.movieIndexName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);

        res = targetClusterOperations.get("/" + testData.aliasName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        if (expectUpdatesOnTarget) {
            assertThat(res.getValue(), containsString(testData.movieIndexName));
        }

        res = targetClusterOperations.get("/_aliases");
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        var verifyAliasWasListed = allOf(containsString(testData.aliasInTemplate), containsString(testData.aliasName));
        assertThat(res.getValue(), expectUpdatesOnTarget ? verifyAliasWasListed : not(verifyAliasWasListed));

        // Check that the templates were migrated
        if (sourceIsES6_8) {
            res = targetClusterOperations.get("/_template/" + testData.indexTemplateName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        } else {
            res = targetClusterOperations.get("/_index_template/" + testData.indexTemplateName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
            var verifyBodyHasComponentTemplate = containsString("composed_of\":[\"" + testData.compoTemplateName + "\"]");
            assertThat(res.getValue(), expectUpdatesOnTarget ? verifyBodyHasComponentTemplate : not(verifyBodyHasComponentTemplate));
        }
    }
}
