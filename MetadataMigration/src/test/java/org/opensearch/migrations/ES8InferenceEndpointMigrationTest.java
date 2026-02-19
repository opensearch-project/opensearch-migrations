package org.opensearch.migrations;

import java.io.File;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * E2E test for inference endpoint migration: reads ES inference endpoints,
 * registers pre-trained models in OS ML Commons, and uses the auto-generated
 * model_ids in the migrated semantic field mappings.
 *
 * Requires ES 8.x with ML enabled (for inference endpoints) and OS 3.5.0+
 * (for the semantic field type).
 */
@Tag("isolatedTest")
@Slf4j
class ES8InferenceEndpointMigrationTest extends BaseMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        return Stream.of(Arguments.of(
            SearchClusterContainer.ES_V8_17,
            SearchClusterContainer.OS_V3_5_0
        ));
    }

    @ParameterizedTest(name = "Inference Endpoint Migration From {0} to {1}")
    @MethodSource(value = "scenarios")
    void inferenceEndpointMigration(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion) {
        // ES 8 containers disable ML by default; enable it for inference endpoint support
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            sourceCluster.withEnv("xpack.ml.enabled", "true");
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            performInferenceEndpointMigrationTest();
        }
    }

    @SneakyThrows
    private void performInferenceEndpointMigrationTest() {
        startClusters();

        // Start trial license on ES for ML features (inference API)
        sourceOperations.post("/_license/start_trial?acknowledge=true", "");

        // Create inference endpoint on ES source
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

        // Create index with semantic_text field
        var indexName = "semantic_test";
        sourceOperations.createIndex(indexName, "{\n" +
            "  \"settings\": { \"number_of_replicas\": 0 },\n" +
            "  \"mappings\": {\n" +
            "    \"properties\": {\n" +
            "      \"content\": {\n" +
            "        \"type\": \"semantic_text\",\n" +
            "        \"inference_id\": \"my-e5-endpoint\"\n" +
            "      },\n" +
            "      \"title\": { \"type\": \"text\" }\n" +
            "    }\n" +
            "  }\n" +
            "}");

        // Take snapshot
        var snapshotName = "inference_endpoint_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());

        // Prepare migration args with source host (for reading inference endpoints)
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
        arguments.sourceArgs.host = sourceCluster.getUrl();

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);
        log.info("CLI output: {}", result.asCliOutput());
        log.info("Exit code: {}, error: {}", result.getExitCode(), result.getErrorMessage());
        assertThat("Migration should not have unexpected failures",
            result.getExitCode(), org.hamcrest.Matchers.lessThanOrEqualTo(1));

        // Verify the semantic_text transform was applied
        var semanticTransformation = result.getTransformations().getTransformerInfos().stream()
            .filter(t -> t.getName().contains("semantic_text"))
            .findAny();
        Assertions.assertTrue(semanticTransformation.isPresent(), "semantic_text transform should be applied");

        // Verify the target index mapping
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        log.info("Target index mapping: {}", res.getValue());

        JsonNode indexJson = MAPPER.readTree(res.getValue());
        JsonNode contentField = indexJson.path(indexName).path("mappings").path("properties").path("content");

        // Should be type "semantic" (not "semantic_text")
        assertThat(contentField.path("type").asText(), equalTo("semantic"));

        // model_id should be the auto-generated OS model_id, not the ES inference_id
        String modelId = contentField.path("model_id").asText();
        log.info("model_id in target mapping: {}", modelId);
        assertThat("model_id should not be the ES inference_id", modelId, not(equalTo("my-e5-endpoint")));
        Assertions.assertFalse(modelId.isEmpty(), "model_id should not be empty");

        // Verify the model exists in OS ML Commons and is a real pre-trained model
        var modelRes = targetOperations.get("/_plugins/_ml/models/" + modelId);
        assertThat("Model should exist in OS ML Commons", modelRes.getKey(), equalTo(200));
        JsonNode modelJson = MAPPER.readTree(modelRes.getValue());
        assertThat("Model should be the pre-trained model",
            modelJson.path("name").asText(), equalTo(InferenceEndpointMigrator.DEFAULT_PRETRAINED_MODEL));

        // Verify no ES-specific fields remain
        assertThat(res.getValue(), not(containsString("semantic_text")));
        assertThat(res.getValue(), not(containsString("inference_id")));
        assertThat(res.getValue(), not(containsString("model_settings")));
    }
}
