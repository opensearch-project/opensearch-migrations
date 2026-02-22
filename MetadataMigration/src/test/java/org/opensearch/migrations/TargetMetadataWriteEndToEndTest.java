package org.opensearch.migrations;

import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.models.DataFilterArgs;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.bulkload.version_os_2_11.IndexMetadataData_OS_2_11;
import org.opensearch.migrations.cluster.ClusterWriterRegistry;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Target-only metadata tests: validates the metadata writing pipeline against each target version
 * using synthetic IR fixtures. No source cluster needed.
 *
 * Combined with SnapshotReaderEndToEndTest (source-only), this achieves O(M+N)
 * test coverage for metadata migration instead of O(M*N).
 */
@Tag("isolatedTest")
class TargetMetadataWriteEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "Target {0}: legacy template")
    @MethodSource("targetVersions")
    void writeLegacyTemplateToTarget(SearchClusterContainer.ContainerVersion targetVersion) {
        try (var target = new SearchClusterContainer(targetVersion)) {
            target.start();
            var context = MetadataMigrationTestContext.factory().noOtelTracking();
            var connectionContext = ConnectionContextTestParams.builder()
                .host(target.getUrl()).build().toConnectionContext();
            var writer = ClusterWriterRegistry.getRemoteWriter(connectionContext, null, new DataFilterArgs(), true);

            // Synthetic GlobalMetadata with a legacy template
            var globalMetadata = syntheticGlobalMetadataWithLegacyTemplate(
                "test_template", "test-*", "keyword"
            );

            var results = writer.getGlobalMetadataCreator()
                .create(globalMetadata, MigrationMode.PERFORM, context.createMetadataMigrationContext());

            assertThat("Legacy template should be created",
                results.getLegacyTemplates().stream().anyMatch(r -> r.wasSuccessful() && "test_template".equals(r.getName())),
                equalTo(true));

            // Verify on target
            var ops = new ClusterOperations(target);
            var res = ops.get("/_template/test_template");
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("test-*"));
        }
    }

    @ParameterizedTest(name = "Target {0}: index with mappings")
    @MethodSource("targetVersions")
    void writeIndexWithMappingsToTarget(SearchClusterContainer.ContainerVersion targetVersion) {
        try (var target = new SearchClusterContainer(targetVersion)) {
            target.start();
            var context = MetadataMigrationTestContext.factory().noOtelTracking();
            var connectionContext = ConnectionContextTestParams.builder()
                .host(target.getUrl()).build().toConnectionContext();
            var writer = ClusterWriterRegistry.getRemoteWriter(connectionContext, null, new DataFilterArgs(), true);

            // Synthetic IndexMetadata
            var indexMetadata = syntheticIndexMetadata("test_index", "idx001");

            var result = writer.getIndexCreator().create(
                indexMetadata,
                MigrationMode.PERFORM,
                AwarenessAttributeSettings.builder().balanceEnabled(false).numberOfAttributeValues(0).build(),
                context.createIndexContext()
            );

            assertThat("Index should be created", result.wasSuccessful(), equalTo(true));

            // Verify on target
            var ops = new ClusterOperations(target);
            var res = ops.get("/test_index");
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("test_index"));
        }
    }

    @ParameterizedTest(name = "Target {0}: index already exists")
    @MethodSource("targetVersions")
    void writeIndexAlreadyExistsOnTarget(SearchClusterContainer.ContainerVersion targetVersion) {
        try (var target = new SearchClusterContainer(targetVersion)) {
            target.start();
            var context = MetadataMigrationTestContext.factory().noOtelTracking();
            var connectionContext = ConnectionContextTestParams.builder()
                .host(target.getUrl()).build().toConnectionContext();
            var writer = ClusterWriterRegistry.getRemoteWriter(connectionContext, null, new DataFilterArgs(), true);

            // Pre-create the index
            var ops = new ClusterOperations(target);
            ops.createIndex("existing_index",
                "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}");

            // Try to create it again via metadata migration
            var indexMetadata = syntheticIndexMetadata("existing_index", "idx002");
            var result = writer.getIndexCreator().create(
                indexMetadata,
                MigrationMode.PERFORM,
                AwarenessAttributeSettings.builder().balanceEnabled(false).numberOfAttributeValues(0).build(),
                context.createIndexContext()
            );

            assertThat("Should report already exists",
                result.getFailureType(),
                equalTo(org.opensearch.migrations.metadata.CreationResult.CreationFailureType.ALREADY_EXISTS));
        }
    }

    /** Creates a synthetic GlobalMetadata with one legacy template */
    private static GlobalMetadata syntheticGlobalMetadataWithLegacyTemplate(
        String templateName, String indexPattern, String fieldType
    ) {
        var root = MAPPER.createObjectNode();
        var templates = root.putObject("templates");
        var template = templates.putObject(templateName);
        template.putArray("index_patterns").add(indexPattern);
        var mappings = template.putObject("mappings");
        var properties = mappings.putObject("properties");
        properties.putObject("field").put("type", fieldType);
        template.putObject("settings").putObject("index").put("number_of_shards", "1");
        return new GlobalMetadataData_OS_2_11(root);
    }

    /** Creates a synthetic IndexMetadata with basic mappings and settings */
    private static IndexMetadata syntheticIndexMetadata(String indexName, String indexId) {
        var root = MAPPER.createObjectNode();
        var mappings = root.putObject("mappings");
        mappings.putObject("properties").putObject("title").put("type", "text");
        root.putObject("aliases");
        var settings = root.putObject("settings");
        settings.put("number_of_shards", "1");
        settings.put("number_of_replicas", "0");
        return new IndexMetadataData_OS_2_11(root, indexId, indexName);
    }
}
