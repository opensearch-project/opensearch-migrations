package org.opensearch.migrations;

import java.io.File;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.transform.TransformerParams;

import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test Class for custom transformation against es8 vector field types
 */
@Tag("isolatedTest")
@Slf4j
class ES8VectorFieldMappingsTransformationTest extends BaseMigrationTest {
    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        // Transformations are differentiated only by source, so lock to a specific target.
        var source = SearchClusterContainer.ES_V8_17;
        var target = SearchClusterContainer.OS_LATEST;

        return Stream.of(Arguments.of(source, target));
    }

    @ParameterizedTest(name = "Custom Transformation From {0} to {1}")
    @MethodSource(value = "scenarios")
    void customTransformationMetadataMigration(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performCustomTransformationTest();
        }
    }

    @SneakyThrows
    private void performCustomTransformationTest() {
        startClusters();

        String requestBody = "{\n" +
                "  \"mappings\": \n" +
                "    {\n" +
                "        \"dynamic\": \"strict\",\n" +
                "        \"properties\": {\n" +
                "          \"my_vector_field\": {\n" +
                "            \"type\": \"dense_vector\",\n" +
                "            \"dims\": 38,\n" +
                "            \"index\": true,\n" +
                "            \"similarity\": \"cosine\",\n" +
                "            \"index_options\": {\n" +
                "              \"type\": \"int8_hnsw\",\n" +
                "              \"m\": 16,\n" +
                "              \"ef_construction\": 100\n" +
                "            }\n" +
                "          },\n" +
                "          \"my_vector_field_2\": {\n" +
                "            \"type\": \"dense_vector\",\n" +
                "            \"dims\": 25,\n" +
                "            \"index\": true,\n" +
                "            \"similarity\": \"dot_product\",\n" +
                "            \"index_options\": {\n" +
                "              \"type\": \"int8_hnsw\",\n" +
                "              \"m\": 16,\n" +
                "              \"ef_construction\": 50\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "}";
        var indexName = "vector";
        sourceOperations.createIndex(indexName, requestBody);


        // TODO: This is brittle since it's pointing to a relative path, and does not work on deployed solutions
        // We should identify a syntax to use a "resources" provided script
        String customTransformationJson = "[{\n" +
            "    \"JsonJSTransformerProvider\": {\n" +
            "      \"initializationScriptFile\": \"../transformation/standardJavascriptTransforms/src/es8-vector-metadata.js\",\n" +
            "      \"bindingsObject\": \"{}\"\n" +
            "    }\n" +
            "  }]";

        var snapshotName = "custom_transformation_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

        // Set up transformation parameters
        arguments.metadataCustomTransformationParams = TestCustomTransformationParams.builder()
                .transformerConfig(customTransformationJson)
                .build();

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        // Verify the migration result
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));
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
