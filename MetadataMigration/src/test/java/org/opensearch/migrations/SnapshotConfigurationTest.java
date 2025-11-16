package org.opensearch.migrations;

import java.io.File;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests metadata migration with different combinations of snapshot compression and global state settings.
 */
@Tag("isolatedTest")
@Slf4j
class SnapshotConfigurationTest extends BaseMigrationTest {

    private enum SnapshotConfiguration {
        COMPRESSED_WITH_GLOBAL_STATE(true, true),
        COMPRESSED_WITHOUT_GLOBAL_STATE(true, false),
        // Validated in existing tests
        //  UNCOMPRESSED_WITH_GLOBAL_STATE(false, true),
        UNCOMPRESSED_WITHOUT_GLOBAL_STATE(false, false);


        private final boolean compressionEnabled;
        private final boolean includeGlobalState;

        SnapshotConfiguration(boolean compressionEnabled, boolean includeGlobalState) {
            this.compressionEnabled = compressionEnabled;
            this.includeGlobalState = includeGlobalState;
        }
    }

    private static Stream<Arguments> snapshotConfigurationScenarios() {
        var targetVersion = SearchClusterContainer.OS_V2_19_1;
        return SupportedClusters.supportedSources(false).stream()
            .filter(
                // Compressed ES 1 snapshots use currently not supported LZF compression
                source -> VersionMatchers.isES_1_X.negate().test(source.getVersion())
            )
            .flatMap(sourceVersion ->
                Stream.of(SnapshotConfiguration.values())
                    .map(config -> Arguments.of(sourceVersion, targetVersion, config))
            );
    }

    @ParameterizedTest(name = "From {0} to {1}, Config: {2}")
    @MethodSource(value = "snapshotConfigurationScenarios")
    void testSnapshotConfiguration(SearchClusterContainer.ContainerVersion sourceVersion,
                                    SearchClusterContainer.ContainerVersion targetVersion,
                                    SnapshotConfiguration config,
                                   @TempDir File localDirectory) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            executeSnapshotConfigurationTest(config, MetadataCommands.EVALUATE, localDirectory);
            executeSnapshotConfigurationTest(config, MetadataCommands.MIGRATE, localDirectory);
        }
    }

    @SneakyThrows
    private void executeSnapshotConfigurationTest(SnapshotConfiguration config, MetadataCommands command, File localDirectory) {
        startClusters();

        // Create test data with various template types
        String legacyTemplateName = "test_legacy_template";
        String indexTemplateName = "test_index_template";
        String componentTemplateName = "test_component_template";
        String indexPattern = "test_*";
        String indexName = "test_2023";
        String aliasName = "test_alias";
        String fieldName = "test_field";

        // Create legacy template
        sourceOperations.createLegacyTemplate(legacyTemplateName, indexPattern);
        
        // Create index template (if supported by source version)
        if (UnboundVersionMatchers.isGreaterOrEqualES_7_X.test(sourceCluster.getContainerVersion().getVersion())) {
            sourceOperations.createIndexTemplate(indexTemplateName, fieldName, indexPattern);
            
            // Create component template with index template
            sourceOperations.createComponentTemplate(componentTemplateName, indexTemplateName, fieldName, indexPattern);
        }

        sourceOperations.createDocument(indexName, "doc1", "{ \"field\": \"value\" }");
        sourceOperations.createAlias(aliasName, indexName);

        // Create snapshot with specific configuration
        var snapshotName = "snap_"  + command.name().toLowerCase();
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(
            sourceCluster,
            snapshotName,
            testSnapshotContext,
            config.compressionEnabled,
            config.includeGlobalState
        );

        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
        arguments.metadataTransformationParams.multiTypeResolutionBehavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION;

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, command);

        // Verify results
        log.info("Snapshot config: {}, Command: {}, Result: {}", config, command, result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var migratedItems = result.getItems();
        
        // When global metadata is included, templates should be migrated
        var expectedTemplates = new java.util.ArrayList<String>();
        expectedTemplates.add(legacyTemplateName);
        if (UnboundVersionMatchers.isGreaterOrEqualES_7_X.test(sourceCluster.getContainerVersion().getVersion())) {
            expectedTemplates.add(indexTemplateName);
        }
        
        assertThat(
                migratedItems.getIndexTemplates().stream()
                .filter(CreationResult::wasSuccessful)
                .map(CreationResult::getName)
                .toList(),
            (config.includeGlobalState) ? hasItems(expectedTemplates.toArray(new String[0])) : Matchers.empty()
        );

        // When global metadata is included and component templates exist, they should be migrated
        if (UnboundVersionMatchers.isGreaterOrEqualES_7_X.test(sourceCluster.getContainerVersion().getVersion())) {
            assertThat(
                migratedItems.getComponentTemplates().stream()
                    .filter(CreationResult::wasSuccessful)
                    .map(CreationResult::getName)
                    .toList(),
                (config.includeGlobalState) ? hasItems(componentTemplateName) : Matchers.empty()
            );
        }

        // Indexes should always be migrated regardless of global metadata setting
        assertThat(
            migratedItems.getIndexes().stream()
                .filter(CreationResult::wasSuccessful)
                .map(CreationResult::getName)
                .toList(),
            hasItems(indexName)
        );

        // Aliases should always be migrated
        assertThat(
            migratedItems.getAliases().stream()
                .filter(CreationResult::wasSuccessful)
                .map(CreationResult::getName)
                .toList(),
            hasItems(aliasName)
        );

        assertNull(migratedItems.getFailureMessage(), "Expected 0 failures but got " + migratedItems.getFailureMessage());

        // Verify target cluster state
        if (MetadataCommands.MIGRATE.equals(command)) {
            verifyTargetCluster(legacyTemplateName, indexTemplateName, componentTemplateName, 
                indexName, aliasName, config.includeGlobalState);
        }
    }

    private void verifyTargetCluster(String legacyTemplateName,
                                     String indexTemplateName,
                                     String componentTemplateName,
                                     String indexName,
                                     String aliasName,
                                     boolean includeGlobalState) {
        // Check index was migrated
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));

        // Check alias was migrated
        res = targetOperations.get("/" + aliasName);
        assertThat(res.getValue(), res.getKey(), equalTo(200));

        // Check templates were migrated (only if global metadata was included)
        if (includeGlobalState) {
            // Check legacy template
            res = targetOperations.get("/_template/" + legacyTemplateName);
            assertThat(res.getValue(), res.getKey(), equalTo(200));
            
            // Check index template and component template (if source version supports them)
            if (UnboundVersionMatchers.isGreaterOrEqualES_7_X.test(sourceCluster.getContainerVersion().getVersion())) {
                res = targetOperations.get("/_index_template/" + indexTemplateName);
                assertThat(res.getValue(), res.getKey(), equalTo(200));
                
                res = targetOperations.get("/_component_template/" + componentTemplateName);
                assertThat(res.getValue(), res.getKey(), equalTo(200));
            }
        }
    }
}
