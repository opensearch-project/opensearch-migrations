package org.opensearch.migrations;

import java.io.File;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test Class for transformation of ES 8.15+ semantic_text field types to OpenSearch text
 */
@Tag("isolatedTest")
@Slf4j
class ES8SemanticTextMappingsTransformationTest extends BaseMigrationTest {
    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        var source = SearchClusterContainer.ES_V8_17;
        var target = SearchClusterContainer.OS_LATEST;
        return Stream.of(Arguments.of(source, target));
    }

    @ParameterizedTest(name = "Semantic Text Transformation From {0} to {1}")
    @MethodSource(value = "scenarios")
    void semanticTextTransformationMetadataMigration(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performSemanticTextTransformationTest();
        }
    }

    @SneakyThrows
    private void performSemanticTextTransformationTest() {
        startClusters();

        // Create inference endpoint required for semantic_text field
        var inferenceBody = "{\n" +
                "  \"service\": \"elasticsearch\",\n" +
                "  \"service_settings\": {\n" +
                "    \"model_id\": \".multilingual-e5-small\",\n" +
                "    \"num_allocations\": 1,\n" +
                "    \"num_threads\": 1\n" +
                "  }\n" +
                "}";
        var inferenceResult = sourceOperations.put("/_inference/text_embedding/my-e5-endpoint", inferenceBody);
        log.info("Inference endpoint creation: status={}, body={}", inferenceResult.getKey(), inferenceResult.getValue());

        String requestBody = "{\n" +
                "  \"settings\": {\n" +
                "    \"number_of_replicas\": 0\n" +
                "  },\n" +
                "  \"mappings\": {\n" +
                "    \"properties\": {\n" +
                "      \"content\": {\n" +
                "        \"type\": \"semantic_text\",\n" +
                "        \"inference_id\": \"my-e5-endpoint\"\n" +
                "      },\n" +
                "      \"title\": {\n" +
                "        \"type\": \"text\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";
        var indexName = "semantic_test";
        sourceOperations.createIndex(indexName, requestBody);

        var snapshotName = "semantic_text_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        // Verify the migration result
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var semanticTransformation = result.getTransformations().getTransformerInfos().stream()
            .filter(t -> t.getName().contains("semantic_text"))
            .findAny();
        Assertions.assertTrue(semanticTransformation.isPresent());
        Assertions.assertEquals("semantic_text to text", semanticTransformation.get().getName());

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));
        // Verify semantic_text was converted to text
        assertThat(res.getValue(), not(containsString("semantic_text")));
        assertThat(res.getValue(), not(containsString("inference_id")));
    }
}
