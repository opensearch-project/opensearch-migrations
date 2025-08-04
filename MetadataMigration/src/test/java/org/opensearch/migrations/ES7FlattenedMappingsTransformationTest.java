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
import static org.hamcrest.MatcherAssert.assertThat;


@Tag("isolatedTest")
@Slf4j
class ES7FlattenedMappingsTransformationTest extends BaseMigrationTest {
    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        var source = SearchClusterContainer.ES_V7_17;
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
            "  \"mappings\": {\n" +
            "    \"dynamic\": \"strict\",\n" +
            "    \"properties\": {\n" +
            "      \"flattened_data\": {\n" +
            "        \"type\": \"flattened\"," +
            "        \"index\": false" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        var indexName = "flattened";
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

        var transformation = result.getTransformations().getTransformerInfos().stream()
            .filter(t -> t.getName().contains("flattened"))
            .findAny();
        Assertions.assertTrue(transformation.isPresent());
        Assertions.assertEquals("flattened to flat_object", transformation.get().getName());

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + indexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(indexName));
        assertThat(res.getValue(), containsString("flat_object"));

    }
}
