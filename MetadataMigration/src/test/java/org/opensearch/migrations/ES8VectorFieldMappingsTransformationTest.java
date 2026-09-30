package org.opensearch.migrations;

import java.io.File;
import java.util.stream.Stream;

import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;

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
        // Verify explicit SQ bits against targets before and after the 3.6 encoder change.
        return Stream.of(SearchClusterContainer.OS_V3_5_0, SearchClusterContainer.OS_LATEST)
            .map(target -> Arguments.of(SearchClusterContainer.ES_V8_17, target));
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
                "  \"settings\": {\n" +
                "    \"number_of_replicas\": 0\n" +
                "  },\n" +
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

        var snapshotName = "custom_transformation_snap";
        var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
        createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
        sourceCluster.copySnapshotData(localDirectory.toString());
        var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

        // Verify the migration result
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        var vectorTransformation = result.getTransformations().getTransformerInfos().stream()
            .filter(t -> t.getName().contains("dense_vector"))
            .findAny();
        Assertions.assertTrue(vectorTransformation.isPresent());
        Assertions.assertEquals("dense_vector to knn_vector", vectorTransformation.get().getName());

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));
        var index = new ObjectMapper().readTree(res.getValue()).path(indexName);
        Assertions.assertTrue(index.path("settings").path("index").path("knn").asBoolean());
        var properties = index.path("mappings").path("properties");
        assertVectorMapping(properties.path("my_vector_field"), 38, "cosinesimil", 100);
        assertVectorMapping(properties.path("my_vector_field_2"), 25, "innerproduct", 50);
    }

    private static void assertVectorMapping(
        JsonNode field, int dimension, String spaceType, int efConstruction
    ) {
        Assertions.assertEquals("knn_vector", field.path("type").asText());
        Assertions.assertEquals(dimension, field.path("dimension").asInt());
        var method = field.path("method");
        Assertions.assertEquals("hnsw", method.path("name").asText());
        Assertions.assertEquals("lucene", method.path("engine").asText());
        Assertions.assertEquals(spaceType, method.path("space_type").asText());
        var parameters = method.path("parameters");
        Assertions.assertEquals(16, parameters.path("m").asInt());
        Assertions.assertEquals(efConstruction, parameters.path("ef_construction").asInt());
        Assertions.assertEquals("sq", parameters.path("encoder").path("name").asText());
        Assertions.assertEquals(7, parameters.path("encoder").path("parameters").path("bits").asInt());
    }
}
