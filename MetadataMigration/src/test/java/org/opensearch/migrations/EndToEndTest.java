package org.opensearch.migrations;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
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
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster.
 */
@Tag("isolatedTest")
@Slf4j
class EndToEndTest extends BaseMigrationTest {

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
                    .flatMap(targetCluster -> templateTypes.stream().flatMap(templateType -> Arrays.stream(TransferMedium.values())
                        .map(transferMedium -> Arguments.of(sourceCluster, targetCluster, transferMedium, templateType))));
            });
    }

    @ParameterizedTest(name = "From version {0} to version {1}, Medium {2}, Command {3}, Template Type {4}")
    @MethodSource(value = "scenarios")
    void metadataCommand(SearchClusterContainer.ContainerVersion sourceVersion,
                         SearchClusterContainer.ContainerVersion targetVersion,
                         TransferMedium medium,
                         TemplateType templateType) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            metadataCommandOnClusters(medium, MetadataCommands.EVALUATE, templateType);
            metadataCommandOnClusters(medium, MetadataCommands.MIGRATE, templateType);
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
    private void metadataCommandOnClusters(TransferMedium medium,
                                           MetadataCommands command,
                                           TemplateType templateType) {
        startClusters();

        var testData = new TestData();

        if (templateType == TemplateType.Legacy) {
            sourceOperations.createLegacyTemplate(testData.indexTemplateName, "blog*");
        } else if (templateType == TemplateType.Index) {
            sourceOperations.createIndexTemplate(testData.indexTemplateName, "author", "blog*");
        } else if (templateType == TemplateType.IndexAndComponent) {
            sourceOperations.createComponentTemplate(testData.compoTemplateName, testData.indexTemplateName, "author", "blog*");
        }

        // Creates a document that uses the template
        sourceOperations.createDocument(testData.blogIndexName, "222", "{\"author\":\"Tobias Funke\"}");
        sourceOperations.createDocument(testData.movieIndexName, "123", "{\"title\":\"This is Spinal Tap\"}");
        sourceOperations.createDocument(testData.indexThatAlreadyExists, "doc66", "{}");

        sourceOperations.createAlias(testData.aliasName, "movies*");

        var arguments = new MigrateOrEvaluateArgs();

        switch (medium) {
            case SnapshotImage:
                var snapshotName = createSnapshot("my_snap_" + command.name().toLowerCase());
                arguments = prepareSnapshotMigrationArgs(snapshotName);
                break;

            case Http:
                arguments.sourceArgs.host = sourceCluster.getUrl();
                arguments.targetArgs.host = targetCluster.getUrl();
                break;
        }

        // Set up data filters
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of();
        dataFilterArgs.componentTemplateAllowlist = List.of(testData.compoTemplateName);
        dataFilterArgs.indexTemplateAllowlist = List.of(testData.indexTemplateName);
        arguments.dataFilterArgs = dataFilterArgs;

        targetOperations.createDocument(testData.indexThatAlreadyExists, "doc77", "{}");

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, command);

        verifyCommandResults(result, templateType, testData);

        verifyTargetCluster(command, templateType, testData);
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

    private void verifyCommandResults(MigrationItemResult result,
                                      TemplateType templateType,
                                      TestData testData) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var migratedItems = result.getItems();
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexTemplates())),
            containsInAnyOrder(testData.indexTemplateName));
        assertThat(
            getNames(getSuccessfulResults(migratedItems.getComponentTemplates())),
            equalTo(templateType.equals(TemplateType.IndexAndComponent) ? List.of(testData.compoTemplateName) : List.of())
        );
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexes())),
            containsInAnyOrder(testData.blogIndexName, testData.movieIndexName));
        assertThat(getNames(getFailedResultsByType(migratedItems.getIndexes(), CreationResult.CreationFailureType.ALREADY_EXISTS)),
            containsInAnyOrder(testData.indexThatAlreadyExists));
        assertThat(getNames(getSuccessfulResults(migratedItems.getAliases())),
            containsInAnyOrder(testData.aliasInTemplate, testData.aliasName));
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
        return items.stream().map(CreationResult::getName).collect(Collectors.toList());
    }

    private void verifyTargetCluster(MetadataCommands command,
                                     TemplateType templateType,
                                     TestData testData) {
        var expectUpdatesOnTarget = MetadataCommands.MIGRATE.equals(command);
        // If the command was migrate, the target cluster should have the items, if not they shouldn't
        var verifyResponseCode = expectUpdatesOnTarget ? equalTo(200) : equalTo(404);

        // Check that the index was migrated
        var res = targetOperations.get("/" + testData.blogIndexName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);

        res = targetOperations.get("/" + testData.movieIndexName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);

        res = targetOperations.get("/" + testData.aliasName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        if (expectUpdatesOnTarget) {
            assertThat(res.getValue(), containsString(testData.movieIndexName));
        }

        res = targetOperations.get("/_aliases");
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        var verifyAliasWasListed = allOf(containsString(testData.aliasInTemplate), containsString(testData.aliasName));
        assertThat(res.getValue(), expectUpdatesOnTarget ? verifyAliasWasListed : not(verifyAliasWasListed));

        // Check that the templates were migrated
        if (templateType.equals(TemplateType.Legacy)) {
            res = targetOperations.get("/_template/" + testData.indexTemplateName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        } else if (templateType.equals(TemplateType.Index) || templateType.equals(TemplateType.IndexAndComponent)) {
            res = targetOperations.get("/_index_template/" + testData.indexTemplateName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
            if (templateType.equals(TemplateType.IndexAndComponent)) {
                var verifyBodyHasComponentTemplate = containsString("composed_of\":[\"" + testData.compoTemplateName + "\"]");
                assertThat(res.getValue(), expectUpdatesOnTarget ? verifyBodyHasComponentTemplate : not(verifyBodyHasComponentTemplate));
            }
        }
    }
}
