package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transform.TransformerParams;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * E2E test verifying that dynamic=false is preserved during metadata migration
 * from ES 6.8 (typed mappings) to OpenSearch targets.
 * Regression test for https://github.com/opensearch-project/opensearch-migrations/pull/2386
 */
@Tag("isolatedTest")
@Slf4j
class DynamicMappingPreservationTest extends BaseMigrationTest {

    @TempDir
    private File localDirectory;

    private static Stream<Arguments> typedSourceScenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V1_3_20),
            Arguments.of(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_19_4)
        );
    }

    private static Stream<Arguments> typelessSourceScenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.OS_V1_3_20, SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "dynamic=false preserved with type sanitization plugin from {0} to {1}")
    @MethodSource(value = "typedSourceScenarios")
    void dynamicFalsePreservedWithTypeSanitizationPlugin(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performTypeSanitizationPluginTest();
        }
    }

    @ParameterizedTest(name = "dynamic=false preserved with default migration (single type) from {0} to {1}")
    @MethodSource(value = "typedSourceScenarios")
    void dynamicFalsePreservedWithDefaultMigration(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performDefaultMigrationSingleTypeTest();
        }
    }

    @ParameterizedTest(name = "dynamic=false preserved with typeless source from {0} to {1}")
    @MethodSource(value = "typelessSourceScenarios")
    void dynamicFalsePreservedWithTypelessSource(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performTypelessSourceTest();
        }
    }

    /**
     * Tests that dynamic=false is preserved when using the TypeMappingSanitizationTransformerProvider
     * plugin to handle type removal during ES 6.8 → OpenSearch migration.
     */
    @SneakyThrows
    private void performTypeSanitizationPluginTest() {
        startClusters();

        var indexName = "dynamic_false_test";

        // ES 6.8 uses typed mappings - dynamic is inside the type wrapper
        var body = "{\n" +
            "  \"settings\": { \"number_of_shards\": 1, \"number_of_replicas\": 0 },\n" +
            "  \"mappings\": {\n" +
            "    \"_doc\": {\n" +
            "      \"dynamic\": false,\n" +
            "      \"properties\": {\n" +
            "        \"name\": { \"type\": \"text\" }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        sourceOperations.createIndex(indexName, body);
        sourceOperations.createDocument(indexName, "1", "{\"name\": \"test\"}");

        var snapshotName = "dynamic_false_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());

        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of(indexName);
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        arguments.dataFilterArgs = dataFilterArgs;

        // Use TypeMappingSanitizationTransformerProvider instead of UNION multi-type resolution
        String transformerConfig = "[\n" +
            "  {\n" +
            "    \"TypeMappingSanitizationTransformerProvider\": {\n" +
            "      \"sourceProperties\": {\n" +
            "        \"version\": {\n" +
            "          \"major\": " + sourceCluster.getContainerVersion().getVersion().getMajor() + ",\n" +
            "          \"minor\": " + sourceCluster.getContainerVersion().getVersion().getMinor() + "\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "]";
        arguments.metadataCustomTransformationParams = TestCustomTransformationParams.builder()
            .transformerConfig(transformerConfig)
            .build();

        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        verifyDynamicFalsePreserved(indexName);
    }

    /**
     * Tests that dynamic=false is preserved during default metadata migration
     * for a single-type ES 6.8 index (no multi-type resolution needed).
     */
    @SneakyThrows
    private void performDefaultMigrationSingleTypeTest() {
        startClusters();

        var indexName = "dynamic_false_single_type";

        // Single type on ES 6.8 with dynamic=false
        var body = "{\n" +
            "  \"settings\": { \"number_of_shards\": 1, \"number_of_replicas\": 0 },\n" +
            "  \"mappings\": {\n" +
            "    \"_doc\": {\n" +
            "      \"dynamic\": false,\n" +
            "      \"properties\": {\n" +
            "        \"title\": { \"type\": \"text\" },\n" +
            "        \"count\": { \"type\": \"integer\" }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        sourceOperations.createIndex(indexName, body);
        sourceOperations.createDocument(indexName, "1", "{\"title\": \"test\", \"count\": 42}");

        var snapshotName = "dynamic_false_single_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());

        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of(indexName);
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        arguments.dataFilterArgs = dataFilterArgs;
        // Default migration - no custom transformer, no multi-type resolution

        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        verifyDynamicFalsePreserved(indexName);
    }

    /**
     * Tests that dynamic=false is preserved when migrating from a typeless source (OS 1)
     * where mappings have no type wrapper.
     */
    @SneakyThrows
    private void performTypelessSourceTest() {
        startClusters();

        var indexName = "dynamic_false_typeless";

        // OS 1 uses typeless mappings - no type wrapper
        var body = "{\n" +
            "  \"settings\": { \"number_of_shards\": 1, \"number_of_replicas\": 0 },\n" +
            "  \"mappings\": {\n" +
            "    \"dynamic\": false,\n" +
            "    \"properties\": {\n" +
            "      \"name\": { \"type\": \"text\" }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        sourceOperations.createIndex(indexName, body);
        sourceOperations.createDocument(indexName, "1", "{\"name\": \"test\"}");

        var snapshotName = "dynamic_false_typeless_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());

        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of(indexName);
        dataFilterArgs.indexTemplateAllowlist = List.of("");
        arguments.dataFilterArgs = dataFilterArgs;

        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        verifyDynamicFalsePreserved(indexName);
    }

    @SneakyThrows
    private void verifyDynamicFalsePreserved(String indexName) {
        var mappingResponse = targetOperations.get("/" + indexName + "/_mapping");
        assertThat(mappingResponse.getKey(), equalTo(200));

        var mapper = new ObjectMapper();
        var mappingJson = mapper.readTree(mappingResponse.getValue());
        var mappings = mappingJson.path(indexName).path("mappings");

        assertThat(
            "dynamic=false should be preserved after migration",
            mappings.path("dynamic").asText(),
            equalTo("false")
        );
        assertThat(
            mappings.path("properties").has("name") || mappings.path("properties").has("title"),
            equalTo(true)
        );
    }

    @Data
    @Builder
    private static class TestCustomTransformationParams implements TransformerParams {
        @Builder.Default
        private String transformerConfigParameterArgPrefix = "";
        private String transformerConfigEncoded;
        private String transformerConfig;
        private String transformerConfigFile;
    }
}
