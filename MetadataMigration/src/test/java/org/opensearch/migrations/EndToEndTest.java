package org.opensearch.migrations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval.MultiTypeResolutionBehavior;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
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
                        .flatMap(targetCluster -> Arrays.stream(TransferMedium.values())
                                .map(transferMedium -> Arguments.of(
                                        sourceCluster,
                                        targetCluster,
                                        transferMedium,
                                        templateTypes)))
                        .collect(Collectors.toList()).stream();
            });
    }

    @ParameterizedTest(name = "From version {0} to version {1}, Medium {2}, Command {3}, Template Type {4}")
    @MethodSource(value = "scenarios")
    void metadataCommand(SearchClusterContainer.ContainerVersion sourceVersion,
                         SearchClusterContainer.ContainerVersion targetVersion,
                         TransferMedium medium,
                         List<TemplateType> templateTypes) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            metadataCommandOnClusters(medium, MetadataCommands.EVALUATE, templateTypes);
            metadataCommandOnClusters(medium, MetadataCommands.MIGRATE, templateTypes);
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
                                           List<TemplateType> templateTypes) {
        startClusters();

        var testData = new TestData();

        for (TemplateType templateType : templateTypes) {
            String uniqueSuffix = templateType.name().toLowerCase();
            String templateName = testData.indexTemplateName + "_" + uniqueSuffix;
            String indexPattern = "blog_" + uniqueSuffix + "_*";
            String fieldName = "author_" + uniqueSuffix;

            if (templateType == TemplateType.Legacy) {
                sourceOperations.createLegacyTemplate(templateName, indexPattern);
                testData.aliasNames.add("alias_legacy");
            } else if (templateType == TemplateType.Index) {
                sourceOperations.createIndexTemplate(templateName, fieldName, indexPattern);
                testData.aliasNames.add("alias_index");
            } else if (templateType == TemplateType.IndexAndComponent) {
                String componentTemplateName = testData.compoTemplateName + "_" + uniqueSuffix;
                sourceOperations.createComponentTemplate(componentTemplateName, templateName, fieldName, indexPattern);
                testData.aliasNames.add("alias_component");
                testData.componentTemplateNames.add(componentTemplateName);
            }
            testData.templateNames.add(templateName);

            // Create documents that use the templates
            String blogIndexName = "blog_" + uniqueSuffix + "_2023";
            sourceOperations.createDocument(blogIndexName, "222", "{\"" + fieldName + "\":\"Tobias Funke\"}");
            testData.blogIndexNames.add(blogIndexName);
        }

        sourceOperations.createDocument(testData.movieIndexName, "123", "{\"title\":\"This is Spinal Tap\"}");
        sourceOperations.createDocument(testData.indexThatAlreadyExists, "doc66", "{}");

        sourceOperations.createAlias(testData.aliasName, "movies*");
        testData.aliasNames.add(testData.aliasName);

        final MigrateOrEvaluateArgs arguments;

        switch (medium) {
            case SnapshotImage:
                var snapshotName = createSnapshot("my_snap_" + command.name().toLowerCase());
                arguments = prepareSnapshotMigrationArgs(snapshotName);
                break;

            case Http:
                arguments = new MigrateOrEvaluateArgs();
                arguments.sourceArgs.host = sourceCluster.getUrl();
                arguments.targetArgs.host = targetCluster.getUrl();
                break;

            default:
                throw new RuntimeException("Invalid Option");
        }
        arguments.metadataTransformationParams.multiTypeResolutionBehavior = MultiTypeResolutionBehavior.UNION;

        // Set up data filters
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = Stream.concat(testData.blogIndexNames.stream(),
            Stream.of(testData.movieIndexName, testData.indexThatAlreadyExists)).collect(Collectors.toList());
        dataFilterArgs.componentTemplateAllowlist = testData.componentTemplateNames;
        dataFilterArgs.indexTemplateAllowlist = testData.templateNames;
        arguments.dataFilterArgs = dataFilterArgs;

        targetOperations.createDocument(testData.indexThatAlreadyExists, "doc77", "{}");

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, command);

        verifyCommandResults(result, templateTypes, testData);

        verifyTargetCluster(command, templateTypes, testData);
    }

    private static class TestData {
        final String compoTemplateName = "simple_component_template";
        final String indexTemplateName = "simple_index_template";
        final String movieIndexName = "movies_2023";
        final String aliasName = "movies-alias";
        final String indexThatAlreadyExists = "already-exists";
        final List<String> blogIndexNames = new ArrayList<>();
        final List<String> templateNames = new ArrayList<>();
        final List<String> componentTemplateNames = new ArrayList<>();
        final List<String> aliasNames = new ArrayList<>();
    }

    private void verifyCommandResults(MigrationItemResult result,
                                      List<TemplateType> templateTypes,
                                      TestData testData) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var migratedItems = result.getItems();
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexTemplates())),
            containsInAnyOrder(testData.templateNames.toArray(new String[0])));
        assertThat(getNames(getSuccessfulResults(migratedItems.getComponentTemplates())),
            containsInAnyOrder(testData.componentTemplateNames.toArray(new String[0])));
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexes())),
            containsInAnyOrder(Stream.concat(testData.blogIndexNames.stream(),
                Stream.of(testData.movieIndexName)).toArray()));
        assertThat(getNames(getFailedResultsByType(migratedItems.getIndexes(),
                CreationResult.CreationFailureType.ALREADY_EXISTS)),
            containsInAnyOrder(testData.indexThatAlreadyExists));
        assertThat(getNames(getSuccessfulResults(migratedItems.getAliases())),
            containsInAnyOrder(testData.aliasNames.toArray(new String[0])));
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
                                     List<TemplateType> templateTypes,
                                     TestData testData) {
        var expectUpdatesOnTarget = MetadataCommands.MIGRATE.equals(command);
        // If the command was migrate, the target cluster should have the items, if not they shouldn't
        var verifyResponseCode = expectUpdatesOnTarget ? equalTo(200) : equalTo(404);

        // Check that the indices were migrated
        for (String blogIndexName : testData.blogIndexNames) {
            var res = targetOperations.get("/" + blogIndexName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        }

        var res = targetOperations.get("/" + testData.movieIndexName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);

        res = targetOperations.get("/" + testData.aliasName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        if (expectUpdatesOnTarget) {
            assertThat(res.getValue(), containsString(testData.movieIndexName));
        }

        res = targetOperations.get("/_aliases");
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        @SuppressWarnings("unchecked")
        var verifyAliasWasListed = allOf(
            testData.aliasNames.stream()
                .map(Matchers::containsString)
                .toArray(Matcher[]::new)
        );
        assertThat(res.getValue(), expectUpdatesOnTarget ? verifyAliasWasListed : not(verifyAliasWasListed));

        // Check that the templates were migrated
        for (String templateName : testData.templateNames) {
            if (templateName.contains("legacy")) {
                res = targetOperations.get("/_template/" + templateName);
            } else {
                res = targetOperations.get("/_index_template/" + templateName);
            }
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        }
    }
}
