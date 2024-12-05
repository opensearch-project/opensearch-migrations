package org.opensearch.migrations;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
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
        return SupportedClusters.sources().stream()
                .flatMap(sourceCluster -> {
                    // Determine applicable template types based on source version
                    List<TemplateType> templateTypes = Stream.concat(
                                    Stream.of(TemplateType.Legacy),
                                    (sourceCluster.getVersion().getMajor() >= 7
                                            ? Stream.of(TemplateType.Index, TemplateType.IndexAndComponent)
                                            : Stream.empty()))
                            .collect(Collectors.toList());

                    return SupportedClusters.targets().stream()
                            .flatMap(targetCluster -> templateTypes.stream().flatMap(templateType -> {
                                // Generate arguments for both HTTP and SnapshotImage transfer mediums
                                Stream<Arguments> httpArgs = Arrays.stream(MetadataCommands.values())
                                        .map(command -> Arguments.of(sourceCluster, targetCluster, TransferMedium.Http, command, templateType));

                                Stream<Arguments> snapshotArgs = Stream.of(
                                        Arguments.of(sourceCluster, targetCluster, TransferMedium.SnapshotImage, MetadataCommands.MIGRATE, templateType)
                                );

                                return Stream.concat(httpArgs, snapshotArgs);
                            }));
                });
    }

    @ParameterizedTest(name = "From version {0} to version {1}, Command {2}, Medium of transfer {3}, and Template Type {4}")
    @MethodSource(value = "scenarios")
    void metadataCommand(ContainerVersion sourceVersion, ContainerVersion targetVersion, TransferMedium medium,
                         MetadataCommands command, TemplateType templateType) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            metadataCommandOnClusters(sourceCluster, targetCluster, medium, command, templateType);
        }
    }

    private enum TransferMedium {
        SnapshotImage,
        Http
    }

    private enum TemplateType {
        Legacy,
        Index,
        IndexAndComponent
    }

    @SneakyThrows
    private void metadataCommandOnClusters(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster,
        final TransferMedium medium,
        final MetadataCommands command,
        final TemplateType templateType
    ) {
        // ACTION: Set up the source/target clusters
        var bothClustersStarted = CompletableFuture.allOf(
            CompletableFuture.runAsync(sourceCluster::start),
            CompletableFuture.runAsync(targetCluster::start)
        );
        bothClustersStarted.join();

        var testData = new TestData();
        var sourceClusterOperations = new ClusterOperations(sourceCluster.getUrl());
        if (templateType == TemplateType.Legacy) {
            sourceClusterOperations.createLegacyTemplate(testData.indexTemplateName, "blog*");
        } else if (templateType == TemplateType.Index) {
            sourceClusterOperations.createIndexTemplate(testData.indexTemplateName, "author", "blog*");
        } else if (templateType == TemplateType.IndexAndComponent) {
            sourceClusterOperations.createComponentTemplate(testData.compoTemplateName, testData.indexTemplateName, "author", "blog*");
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
                arguments.sourceVersion = sourceCluster.getContainerVersion().getVersion();
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

        verifyCommandResults(result, templateType, testData);

        verifyTargetCluster(targetClusterOperations, command, templateType, testData);
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
        TemplateType templateType,
        TestData testData) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var migratedItems = result.getItems();
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexTemplates())), containsInAnyOrder(testData.indexTemplateName));
        assertThat(getNames(getSuccessfulResults(migratedItems.getComponentTemplates())), equalTo(templateType.equals(TemplateType.IndexAndComponent) ? List.of(testData.compoTemplateName) : List.of()));
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexes())), containsInAnyOrder(testData.blogIndexName, testData.movieIndexName));
        assertThat(getNames(getFailedResultsByType(migratedItems.getIndexes(), CreationResult.CreationFailureType.ALREADY_EXISTS)), containsInAnyOrder(testData.indexThatAlreadyExists));
        assertThat(getNames(getSuccessfulResults(migratedItems.getAliases())), containsInAnyOrder(testData.aliasInTemplate, testData.aliasName));

    }

    private List<CreationResult> getSuccessfulResults(List<CreationResult> results) {
        return results.stream()
                .filter(CreationResult::wasSuccessful)
                .collect(Collectors.toList());
    }

    private List<CreationResult> getFailedResultsByType(List<CreationResult> results, CreationResult.CreationFailureType failureType) {
        return results.stream()
                .filter(r -> failureType.equals(r.getFailureType()))
                .collect(Collectors.toList());
    }

    private List<String> getNames(List<CreationResult> items) {
        return items.stream().map(r -> r.getName()).collect(Collectors.toList());
    }

    private void verifyTargetCluster(
        ClusterOperations targetClusterOperations,
        MetadataCommands command,
        TemplateType templateType,
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
        if (templateType.equals(TemplateType.Legacy)) {
            res = targetClusterOperations.get("/_template/" + testData.indexTemplateName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        } else if(templateType.equals(TemplateType.Index) || templateType.equals(TemplateType.IndexAndComponent)) {
            res = targetClusterOperations.get("/_index_template/" + testData.indexTemplateName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
            if (templateType.equals(TemplateType.IndexAndComponent)) {
                var verifyBodyHasComponentTemplate = containsString("composed_of\":[\"" + testData.compoTemplateName + "\"]");
                assertThat(res.getValue(), expectUpdatesOnTarget ? verifyBodyHasComponentTemplate : not(verifyBodyHasComponentTemplate));
            }
        }
    }
}
