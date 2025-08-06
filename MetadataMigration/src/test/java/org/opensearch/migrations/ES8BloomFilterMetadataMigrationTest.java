package org.opensearch.migrations;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval.MultiTypeResolutionBehavior;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
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
import static org.hamcrest.Matchers.hasItems;

/**
 * Tests focused on all ES 8x minor versions, performing a migration with various BloomFilter setting, and validation on the target cluster.
 */
@Tag("isolatedTest")
@Slf4j
public class ES8BloomFilterMetadataMigrationTest extends BaseMigrationTest {

    @TempDir
    protected File localDirectory;

    private static final Set<SearchClusterContainer.ContainerVersion> ALLOWED_TARGETS = Set.of(
        SearchClusterContainer.OS_V2_19_1
    );

    // Utility: combine sources from supportedPairs and extendedSources, filtered for ES 8.x
    private static Stream<SearchClusterContainer.ContainerVersion> combinedEs8xSources() {
        var supportedSources = SupportedClusters.supportedPairs(false).stream()
            .map(pair -> pair.source())
            .filter(s -> VersionMatchers.isES_8_X.test(s.getVersion()));

        var extendedSources = SupportedClusters.extendedSources().stream()
            .filter(s -> VersionMatchers.isES_8_X.test(s.getVersion()));

        return Stream.concat(supportedSources, extendedSources).distinct();
    }

    // Convert sources + targets + mediums into full Arguments stream
    private static Stream<Arguments> es8xCombinedScenarios() {
        return combinedEs8xSources()
            .flatMap(source -> ALLOWED_TARGETS.stream()
                .flatMap(target -> Arrays.stream(TransferMedium.values())
                    .map(medium -> Arguments.of(
                        source,
                        target,
                        medium,
                        List.of(TemplateType.Legacy)
                    ))
                )
            );
    }

    private enum TransferMedium {
        SnapshotImage,
        Http
    }

    private enum TemplateType {
        Legacy
    }

    @ParameterizedTest(name = "ES8x {0} -> {1} via {2}")
    @MethodSource("es8xCombinedScenarios")
    void es8xBloomFilterMetadataMigration(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion,
            TransferMedium medium,
            List<TemplateType> templateTypes
    ) {
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

    @SneakyThrows
    private void metadataCommandOnClusters(
            TransferMedium medium,
            MetadataCommands command,
            List<TemplateType> templateTypes
    ) {
        startClusters();

        var testData = new TestData();

        for (TemplateType templateType : templateTypes) {
            String uniqueSuffix = templateType.name().toLowerCase();
            String templateName = testData.indexTemplateName + "_" + uniqueSuffix;
            String indexPattern = "blog_" + uniqueSuffix + "_*";

            // For ES 8.x, only Legacy template is in use:
            sourceOperations.createLegacyTemplate(templateName, indexPattern);
            testData.templateNames.add(templateName);
            testData.aliasNames.add("alias_legacy");

            // --- SCENARIO 1: Template index, then docs ---
            String blogIndexName = "blog_" + uniqueSuffix + "_2023";
            sourceOperations.createDocument(blogIndexName, "201", "{ \"name\": \"bob\", \"is_active\": true }");
            testData.blogIndexNames.add(blogIndexName);
        }

        // --- SCENARIO 2: Create movie docs in a new index (no template) ---
        sourceOperations.createDocument(testData.movieIndexName, "301", "{ \"age\": 55, \"is_active\": false }");

        // --- SCENARIO 3: Create book doc, disable bloom, add another doc ---
        sourceOperations.createDocument(testData.bookIndexName, "401", "{ \"age\": 55, \"is_active\": false }");
        sourceOperations.disableBloomFilterAndRefresh(testData.bookIndexName);
        sourceOperations.createDocument(testData.bookIndexName, "402", "{ \"age\": 55, \"is_active\": false }");
        sourceOperations.refreshIndex(testData.bookIndexName);

        // --- SCENARIO 4: Index already exists ---
        sourceOperations.createDocument(testData.indexThatAlreadyExists, "doc66", "{ \"age\": 99, \"is_active\": true }");

        // --- SCENARIO 5: Alias creation ---
        sourceOperations.createAlias(testData.aliasName, "movies*");
        testData.aliasNames.add(testData.aliasName);

        // Prepare migration arguments as before
        MigrateOrEvaluateArgs arguments;
        switch (medium) {
            case SnapshotImage:
                var snapshotName = "my_snap_" + command.name().toLowerCase();
                var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
                createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
                sourceCluster.copySnapshotData(localDirectory.toString());
                arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
                break;
            case Http:
                arguments = new MigrateOrEvaluateArgs();
                arguments.sourceArgs.host = sourceCluster.getUrl();
                arguments.targetArgs.host = targetCluster.getUrl();
                break;
            default:
                throw new RuntimeException("Invalid Option");
        }

        // Typical target setup and migration as in your base test
        if (!(SupportedClusters.supportedTargets(false)
                .stream()
                .anyMatch(v -> v.equals(targetCluster.getContainerVersion().getVersion())))) {
            arguments.versionStrictness.allowLooseVersionMatches = true;
        }
        arguments.metadataTransformationParams.multiTypeResolutionBehavior = MultiTypeResolutionBehavior.UNION;
        targetOperations.createDocument(testData.indexThatAlreadyExists, "doc77", "{}");

        MigrationItemResult result = executeMigration(arguments, command);

        verifyCommandResults(result, templateTypes, testData);
        verifyTargetCluster(command, templateTypes, testData);
    }

    private static class TestData {
        final String indexTemplateName = "simple_index_template";
        final String movieIndexName = "movies_2023";
        final String aliasName = "movies-alias";
        final String indexThatAlreadyExists = "already-exists";
        final List<String> blogIndexNames = new ArrayList<>();
        final List<String> templateNames = new ArrayList<>();
        final List<String> componentTemplateNames = new ArrayList<>();
        final List<String> aliasNames = new ArrayList<>();
        final String bookIndexName = "books_2023";
    }

    private void verifyCommandResults(MigrationItemResult result,
                                      List<TemplateType> templateTypes,
                                      TestData testData) {
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var migratedItems = result.getItems();

        // Legacy-only scenario means no component templates were created.
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexTemplates())),
                hasItems(testData.templateNames.toArray(new String[0])));

        assertThat(getNames(getSuccessfulResults(migratedItems.getComponentTemplates())),
                hasItems(testData.componentTemplateNames.toArray(new String[0])));  // Empty list for ES8x

        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexes())),
                hasItems(Stream.concat(testData.blogIndexNames.stream(),
                        Stream.of(testData.movieIndexName)).toArray(String[]::new)));

        assertThat(getNames(getFailedResultsByType(migratedItems.getIndexes(),
                        CreationResult.CreationFailureType.ALREADY_EXISTS)),
                hasItems(testData.indexThatAlreadyExists));

        assertThat(getNames(getSuccessfulResults(migratedItems.getAliases())),
                hasItems(testData.aliasNames.toArray(new String[0])));
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
