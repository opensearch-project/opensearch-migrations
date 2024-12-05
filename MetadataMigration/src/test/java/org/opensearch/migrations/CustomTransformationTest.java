package org.opensearch.migrations;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
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
 * Test class to verify custom transformations during metadata migrations.
 */
@Tag("isolatedTest")
@Slf4j
class CustomTransformationTest {

    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        // Define scenarios with different source and target cluster versions
        return SupportedClusters.sources().stream()
                .flatMap(sourceCluster ->
                        SupportedClusters.targets().stream()
                                .map(targetCluster -> Arguments.of(sourceCluster, targetCluster))
                );
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
            performCustomTransformationTest(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void performCustomTransformationTest(
            final SearchClusterContainer sourceCluster,
            final SearchClusterContainer targetCluster
    ) {
        // Start both source and target clusters asynchronously
        CompletableFuture.allOf(
                CompletableFuture.runAsync(sourceCluster::start),
                CompletableFuture.runAsync(targetCluster::start)
        ).join();

        var sourceOperations = new ClusterOperations(sourceCluster.getUrl());
        var targetOperations = new ClusterOperations(targetCluster.getUrl());

        // Test data
        var originalIndexName = "test_index";
        var transformedIndexName = "transformed_index";
        var documentId = "1";
        var documentContent = "{\"field\":\"value\"}";

        // Create index and add a document on the source cluster
        sourceOperations.createIndex(originalIndexName);
        sourceOperations.createDocument(originalIndexName, documentId, documentContent);

        // Create legacy template
        var legacyTemplateName = "legacy_template";
        var legacyTemplatePattern = "legacy_*";
        sourceOperations.createLegacyTemplate(legacyTemplateName, legacyTemplatePattern);

        // Create index template
        var indexTemplateName = "index_template";
        var indexTemplatePattern = "index*";

        // Create component template
        var componentTemplateName = "component_template";
        var componentTemplateMode = "mode_value"; // Replace with actual mode if applicable
        boolean newComponentCompatible = sourceCluster.getContainerVersion().getVersion().getMajor() >= 7;
        if (newComponentCompatible) {
            sourceOperations.createIndexTemplate(indexTemplateName, "dummy", indexTemplatePattern);

            var componentTemplateAdditionalParam = "additional_param"; // Replace with actual param if applicable
            sourceOperations.createComponentTemplate(componentTemplateName, indexTemplateName, componentTemplateAdditionalParam, "index*");
        }

        // Create index that matches the templates
        var legacyIndexName = "legacy_index";
        var indexIndexName = "index_index";
        sourceOperations.createIndex(legacyIndexName);
        sourceOperations.createIndex(indexIndexName);

        // Define custom transformations for index, legacy, and component templates
        String customTransformationJson = "[\n" +
            "  {\n" +
            "    \"JsonConditionalTransformerProvider\": [\n" +
            "      {\"JsonJMESPathPredicateProvider\": { \"script\": \"name == 'test_index'\"}},\n" +
            "      [\n" +
            "        {\"JsonJoltTransformerProvider\": { \n" +
            "          \"script\": {\n" +
            "            \"operation\": \"modify-overwrite-beta\",\n" +
            "            \"spec\": {\n" +
            "              \"name\": \"transformed_index\"\n" +
            "            }\n" +
            "          } \n" +
            "        }}\n" +
            "      ]\n" +
            "    ]\n" +
            "  },\n" +
            "  {\n" +
            "    \"JsonConditionalTransformerProvider\": [\n" +
            "      {\"JsonJMESPathPredicateProvider\": { \"script\": \"type == 'template' && name == 'legacy_template'\"}},\n" +
            "      [\n" +
            "        {\"JsonJoltTransformerProvider\": { \n" +
            "          \"script\": {\n" +
            "            \"operation\": \"modify-overwrite-beta\",\n" +
            "            \"spec\": {\n" +
            "              \"name\": \"transformed_legacy_template\"\n" +
            "            }\n" +
            "          } \n" +
            "        }}\n" +
            "      ]\n" +
            "    ]\n" +
            "  },\n" +
            "  {\n" +
            "    \"JsonConditionalTransformerProvider\": [\n" +
            "      {\"JsonJMESPathPredicateProvider\": { \"script\": \"type == 'index_template' && name == 'index_template'\"}},\n" +
            "      [\n" +
            "        {\"JsonJoltTransformerProvider\": { \n" +
            "          \"script\": {\n" +
            "            \"operation\": \"modify-overwrite-beta\",\n" +
            "            \"spec\": {\n" +
            "              \"name\": \"transformed_index_template\",\n" +
            "              \"body\": {\n" +
            "                \"composed_of\": {\n" +
            "                  \"[0]\": \"transformed_component_template\"\n" +
            "                }\n" +
            "              }\n" +
            "            }\n" +
            "          }\n" +
            "        }}\n" +
            "      ]\n" +
            "    ]\n" +
            "  },\n" +
            "  {\n" +
            "    \"JsonConditionalTransformerProvider\": [\n" +
            "      {\"JsonJMESPathPredicateProvider\": { \"script\": \"type == 'component_template' && name == 'component_template'\"}},\n" +
            "      [\n" +
            "        {\"JsonJoltTransformerProvider\": { \n" +
            "          \"script\": {\n" +
            "            \"operation\": \"modify-overwrite-beta\",\n" +
            "            \"spec\": {\n" +
            "              \"name\": \"transformed_component_template\"\n" +
            "            }\n" +
            "          } \n" +
            "        }}\n" +
            "      ]\n" +
            "    ]\n" +
            "  }\n" +
            "]";

        var arguments = new MigrateOrEvaluateArgs();

        // Use SnapshotImage as the transfer medium
        var snapshotName = "custom_transformation_snap";
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var sourceClient = new OpenSearchClient(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
        var snapshotCreator = new FileSystemSnapshotCreator(
                snapshotName,
                sourceClient,
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR,
                List.of(),
                snapshotContext.createSnapshotCreateContext()
        );
        SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
        sourceCluster.copySnapshotData(localDirectory.toString());
        arguments.fileSystemRepoPath = localDirectory.getAbsolutePath();
        arguments.snapshotName = snapshotName;
        arguments.sourceVersion = sourceCluster.getContainerVersion().getVersion();

        arguments.targetArgs.host = targetCluster.getUrl();

        // Set up data filters to include only the test index and templates
        var dataFilterArgs = new DataFilterArgs();
        dataFilterArgs.indexAllowlist = List.of(originalIndexName, legacyIndexName, indexIndexName, transformedIndexName);
        dataFilterArgs.indexTemplateAllowlist = List.of(indexTemplateName, legacyTemplateName, "transformed_legacy_template", "transformed_index_template");
        dataFilterArgs.componentTemplateAllowlist = List.of(componentTemplateName, "transformed_component_template");
        arguments.dataFilterArgs = dataFilterArgs;

        // Specify the custom transformer configuration
        arguments.metadataTransformationParams = TestTransformationParams.builder()
                .transformerConfig(customTransformationJson)
                .build();

        // Execute the migration with the custom transformation
        var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
        var metadata = new MetadataMigration();

        MigrationItemResult result = metadata.migrate(arguments).execute(metadataContext);

        // Verify the migration result
        log.info(result.asCliOutput());
        assertThat(result.getExitCode(), equalTo(0));

        // Verify that the transformed index exists on the target cluster
        var res = targetOperations.get("/" + transformedIndexName);
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString(transformedIndexName));

        // Verify that the original index does not exist on the target cluster
        res = targetOperations.get("/" + originalIndexName);
        assertThat(res.getKey(), equalTo(404));

        // Verify that the transformed legacy template exists on the target cluster
        res = targetOperations.get("/_template/transformed_legacy_template");
        assertThat(res.getKey(), equalTo(200));
        assertThat(res.getValue(), containsString("transformed_legacy_template"));

        // Verify that the original legacy template does not exist on the target cluster
        res = targetOperations.get("/_template/" + legacyTemplateName);
        assertThat(res.getKey(), equalTo(404));

        if (newComponentCompatible) {
            // Verify that the transformed index template exists on the target cluster
            res = targetOperations.get("/_index_template/transformed_index_template");
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("transformed_index_template"));

            // Verify that the original index template does not exist on the target cluster
            res = targetOperations.get("/_index_template/" + indexTemplateName);
            assertThat(res.getKey(), equalTo(404));

            // Verify that the transformed component template exists on the target cluster
            res = targetOperations.get("/_component_template/transformed_component_template");
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("transformed_component_template"));

            // Verify that the original component template does not exist on the target cluster
            res = targetOperations.get("/_component_template/" + componentTemplateName);
            assertThat(res.getKey(), equalTo(404));
        }
    }

    @Data
    @Builder
    private static class TestTransformationParams implements TransformerParams {
        @Builder.Default
        private String transformerConfigParameterArgPrefix = "";
        private String transformerConfigEncoded;
        private String transformerConfig;
        private String transformerConfigFile;
    }
}
