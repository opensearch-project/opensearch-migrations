package org.opensearch.migrations.bulkload;

import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.pipeline.MetadataMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchMetadataSink;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceFactory;
import org.opensearch.migrations.bulkload.pipeline.source.SourcelessExtractionConfig;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Target-only metadata write tests parameterized over all target versions.
 * Validates index creation, template handling, and idempotent creates.
 */
@Slf4j
@Tag("isolatedTest")
public class TargetMetadataWriteEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Arguments> targetVersions() {
        return SupportedClusters.targets().stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "Index creation → {0}")
    @MethodSource("targetVersions")
    void metadataSinkCreatesIndex(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchMetadataSink(client);

            sink.createIndex(new IndexMetadataSnapshot("meta_test_idx", 2, 0, null, null, null)).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("meta_test_idx", context.createUnboundRequestContext());
            assertEquals(200, resp.statusCode, "Index should exist");
        }
    }

    @ParameterizedTest(name = "Idempotent create → {0}")
    @MethodSource("targetVersions")
    void metadataSinkHandlesAlreadyExists(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var sink = new OpenSearchMetadataSink(client);

            var meta = new IndexMetadataSnapshot("idempotent_idx", 1, 0, null, null, null);
            sink.createIndex(meta).block();
            // Second create should not throw
            sink.createIndex(meta).block();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            var resp = restClient.get("idempotent_idx", context.createUnboundRequestContext());
            assertEquals(200, resp.statusCode, "Index should still exist");
        }
    }

    @ParameterizedTest(name = "MetadataMigrationPipeline → {0}")
    @MethodSource("targetVersions")
    void metadataPipelineCreatesIndices(ContainerVersion targetVersion) {
        try (var cluster = new SearchClusterContainer(targetVersion)) {
            cluster.start();
            var client = createClient(cluster);
            var metaSink = new OpenSearchMetadataSink(client);

            var configs = List.of(
                SourcelessExtractionConfig.withDefaults("meta_pipe_a", 1, 10),
                SourcelessExtractionConfig.withDefaults("meta_pipe_b", 2, 5)
            );
            var metaSource = DocumentSourceFactory.createSourcelessMetadata(configs);

            new MetadataMigrationPipeline(metaSource, metaSink).migrateAll().blockLast();

            var restClient = createRestClient(cluster);
            var context = DocumentMigrationTestContext.factory().noOtelTracking();
            assertTrue(restClient.get("meta_pipe_a", context.createUnboundRequestContext()).statusCode == 200);
            assertTrue(restClient.get("meta_pipe_b", context.createUnboundRequestContext()).statusCode == 200);
        }
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createClient(
        SearchClusterContainer cluster
    ) {
        var ctx = ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext();
        return new OpenSearchClientFactory(ctx).determineVersionAndCreate();
    }

    @SneakyThrows
    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        );
    }
}
