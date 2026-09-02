package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts the shard live-doc count equals what the source cluster reports as
 * {@code _cat/indices docs.count}, for an index containing nested documents.
 *
 * <p>Goes through a real cluster, a real snapshot and the real unpack + reader path rather than a
 * hand-built Lucene index, so a regression anywhere along that chain (including the soft-delete
 * wrapper) fails the test. The index is nested on purpose: {@code docs.count} counts nested children
 * and {@code _count} does not, so a flat index could not tell the two apart.
 */
@Slf4j
@Tag("isolatedTest")
public class ShardLiveDocCountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NESTED_INDEX = "nested_live_count";

    @TempDir
    private File snapshotDirectory;
    @TempDir
    private File unpackDirectory;

    private static Stream<Arguments> sources() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ES_V7_10_2),
            Arguments.of(SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "Source {0}")
    @MethodSource("sources")
    public void liveDocCountMatchesCatIndices(final SearchClusterContainer.ContainerVersion sourceVersion) {
        try (final var sourceCluster = new SearchClusterContainer(sourceVersion)) {
            runScenario(sourceCluster);
        }
    }

    @SneakyThrows
    private void runScenario(final SearchClusterContainer sourceCluster) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        try {
            sourceCluster.start();

            var sourceOps = new ClusterOperations(sourceCluster);
            var sourceVersion = sourceCluster.getContainerVersion().getVersion();

            sourceOps.createIndex(NESTED_INDEX,
                "{"
                + "  \"settings\": {\"number_of_shards\": 1, \"number_of_replicas\": 0},"
                + "  \"mappings\": {\"properties\": {"
                + "    \"title\": {\"type\": \"text\"},"
                + "    \"answers\": {\"type\": \"nested\", \"properties\": {"
                + "      \"user\": {\"type\": \"keyword\"}, \"score\": {\"type\": \"integer\"}}}"
                + "  }}"
                + "}");

            int[] childCounts = { 2, 3, 0, 1, 4 };
            for (int i = 0; i < childCounts.length; i++) {
                var answers = new StringBuilder("[");
                for (int a = 0; a < childCounts[i]; a++) {
                    if (a > 0) answers.append(",");
                    answers.append("{\"user\":\"u").append(a).append("\",\"score\":").append(a).append("}");
                }
                answers.append("]");
                sourceOps.createDocument(NESTED_INDEX, "root" + i,
                    "{\"title\":\"doc " + i + "\",\"answers\":" + answers + "}");
            }
            sourceOps.refresh(NESTED_INDEX);

            // Update a root so the shard carries deleted docs and liveDocs is non-null.
            sourceOps.createDocument(NESTED_INDEX, "root0",
                "{\"title\":\"doc 0 updated\",\"answers\":[{\"user\":\"z\",\"score\":9}]}");
            sourceOps.refresh(NESTED_INDEX);
            sourceOps.get("/" + NESTED_INDEX + "/_flush?wait_if_ongoing=true");

            int countApi = countViaCountApi(sourceOps, NESTED_INDEX);
            int docsCount = docsCountViaStats(sourceOps, NESTED_INDEX);
            log.info("source _count={}, source docs.count={}", countApi, docsCount);

            Assertions.assertTrue(docsCount > countApi,
                "fixture must produce nested children so docs.count exceeds _count; got docs.count="
                    + docsCount + " _count=" + countApi);

            var snapshotName = "live_count_snap";
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
            var snapshotCreator = new SnapshotCreator(
                snapshotName,
                "live_count_repo",
                clientFactory.determineVersionAndCreate(),
                RepoUri.parse(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR),
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(snapshotDirectory.toString());

            long liveDocs = countLiveDocsFromSnapshot(snapshotDirectory.toPath(), snapshotName, sourceVersion);

            Assertions.assertEquals(docsCount, liveDocs,
                "live doc count must equal the source's _cat/indices docs.count, which includes "
                    + "nested children");
            Assertions.assertTrue(liveDocs > countApi,
                "live doc count must exceed _count on a nested index; if these are equal the count "
                    + "is silently excluding nested children");
        } finally {
            FileSystemUtils.deleteDirectories(snapshotDirectory.toString(), unpackDirectory.toString());
        }
    }

    /** Unpacks the snapshot's only shard and counts live docs through the production reader path. */
    private long countLiveDocsFromSnapshot(Path repoPath, String snapshotName,
                                           org.opensearch.migrations.Version version) throws Exception {
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        var sourceRepo = new FileSystemRepo(repoPath, fileFinder);
        var snapshotReader = SnapshotReaderRegistry.getSnapshotReader(version, sourceRepo, true);

        var indexMetadata = snapshotReader.getIndexMetadata().fromRepo(snapshotName, NESTED_INDEX);
        var shardMetadata = snapshotReader.getShardMetadata().fromRepo(snapshotName, NESTED_INDEX, 0);
        Assertions.assertEquals(1, indexMetadata.getNumberOfShards(), "fixture expects a single shard");

        var unpacker = new SnapshotShardUnpacker.Factory(
            new SourceRepoAccessor(sourceRepo), unpackDirectory.toPath())
            .create(new java.util.HashSet<>(shardMetadata.getFiles()),
                NESTED_INDEX, shardMetadata.getIndexId(), 0);
        unpacker.unpack();

        Path shardPath = unpackDirectory.toPath().resolve(NESTED_INDEX).resolve("0");
        LuceneIndexReader indexReader = new LuceneIndexReader.Factory(snapshotReader).getReader(shardPath);
        try (var directoryReader = indexReader.getReader(shardMetadata.getSegmentFileName())) {
            return LuceneReader.countLiveDocs(directoryReader);
        }
    }

    @SneakyThrows
    private static int countViaCountApi(ClusterOperations ops, String index) {
        var response = ops.get("/" + index + "/_count");
        Assertions.assertEquals(200, response.getKey(), "unexpected _count response: " + response.getValue());
        return MAPPER.readTree(response.getValue()).get("count").asInt();
    }

    @SneakyThrows
    private static int docsCountViaStats(ClusterOperations ops, String index) {
        var response = ops.get("/" + index + "/_stats/docs");
        Assertions.assertEquals(200, response.getKey(), "unexpected _stats response: " + response.getValue());
        return MAPPER.readTree(response.getValue())
            .path("indices").path(index).path("primaries").path("docs").path("count").asInt();
    }
}
