package org.opensearch.migrations.bulkload;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

/**
 * Verifies that the per-shard live document count reported by the backfill equals the
 * source's {@code <index>/_count} for an index containing nested documents.
 *
 * <p>This is the case that distinguishes the three counts a cluster exposes. For an index
 * with nested documents:
 * <ul>
 *   <li>{@code <index>/_count} counts root documents only — the number we must report</li>
 *   <li>{@code _cat/indices docs.count} counts roots AND nested children</li>
 *   <li>Lucene's {@code numDocs()} likewise counts children</li>
 * </ul>
 * Nested children are separate Lucene documents that carry no stored {@code _id}, so
 * {@code LuceneReader.getDocument} skips them and they never reach the target. The test
 * asserts the migration's own reported total lands on the first of those three numbers, and
 * additionally asserts the source really does exhibit the divergence — otherwise the test
 * could pass against a fixture that never had nested children at all.
 */
@Slf4j
public class NestedDocCountTest extends SourceTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NESTED_INDEX = "nested_docs";
    private static final int SHARDS = 1;


    @TempDir
    private File localDirectory;

    private static Stream<Arguments> scenarios() {
        // Lucene 9 (OS 2.19) and Lucene 8 (ES 7.10) sources. Root-only markers differ by
        // Lucene generation, so cover more than one.
        return Stream.of(
            Arguments.of(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_4),
            Arguments.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "Source {0} to Target {1}")
    @MethodSource("scenarios")
    public void liveDocCountExcludesNestedChildren(
        final SearchClusterContainer.ContainerVersion sourceVersion,
        final SearchClusterContainer.ContainerVersion targetVersion
    ) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            runNestedDocCountScenario(sourceCluster, targetCluster);
        }
    }

    @SneakyThrows
    private void runNestedDocCountScenario(
        final SearchClusterContainer sourceCluster,
        final SearchClusterContainer targetCluster
    ) {
        final var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        final var testContext = DocumentMigrationTestContext.factory().noOtelTracking();

        try {
            Startables.deepStart(sourceCluster, targetCluster).join();

            var sourceOps = new ClusterOperations(sourceCluster);
            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            boolean typedMappings = VersionMatchers.isES_7_X.test(sourceVersion)
                ? false
                : org.opensearch.migrations.UnboundVersionMatchers.isBelowES_7_X.test(sourceVersion);

            String properties =
                "    \"title\": {\"type\": \"text\"}," +
                "    \"answers\": {" +
                "      \"type\": \"nested\"," +
                "      \"properties\": {" +
                "        \"user\": {\"type\": \"keyword\"}," +
                "        \"score\": {\"type\": \"integer\"}" +
                "      }" +
                "    }";
            String mappings = typedMappings
                ? "{\"" + sourceOps.defaultDocType() + "\": {\"properties\": {" + properties + "}}}"
                : "{\"properties\": {" + properties + "}}";

            sourceOps.createIndex(NESTED_INDEX,
                "{" +
                "  \"settings\": {\"number_of_shards\": " + SHARDS + ", \"number_of_replicas\": 0}," +
                "  \"mappings\": " + mappings +
                "}");

            // 5 root documents with varying numbers of nested children, including one with
            // none. Children are indexed as separate Lucene docs in the same block as their
            // root, so docs.count will exceed _count.
            int[] childCounts = { 2, 3, 0, 1, 4 };
            int expectedChildren = 0;
            for (int i = 0; i < childCounts.length; i++) {
                var answers = new StringBuilder("[");
                for (int a = 0; a < childCounts[i]; a++) {
                    if (a > 0) answers.append(",");
                    answers.append("{\"user\":\"u").append(a).append("\",\"score\":").append(a).append("}");
                }
                answers.append("]");
                sourceOps.createDocument(NESTED_INDEX, "root" + i,
                    "{\"title\":\"doc " + i + "\",\"answers\":" + answers + "}");
                expectedChildren += childCounts[i];
            }
            sourceOps.refresh(NESTED_INDEX);

            // Update one root so the shard also carries deleted Lucene docs. The update
            // replaces the whole block (root + its children), so liveDocs is non-null and the
            // count must ignore the dead docs.
            sourceOps.createDocument(NESTED_INDEX, "root0",
                "{\"title\":\"doc 0 updated\",\"answers\":[{\"user\":\"z\",\"score\":9}]}");
            sourceOps.refresh(NESTED_INDEX);
            expectedChildren = expectedChildren - childCounts[0] + 1;

            int expectedRoots = childCounts.length;

            // Establish the divergence really exists on this source, so a green assertion
            // below cannot be an artifact of a fixture without nested children.
            int sourceCount = countViaCountApi(sourceOps, NESTED_INDEX);
            int sourceDocsCount = docsCountViaStats(sourceOps, NESTED_INDEX);
            log.info("source _count={}, source docs.count={}, expectedRoots={}, expectedChildren={}",
                sourceCount, sourceDocsCount, expectedRoots, expectedChildren);

            Assertions.assertEquals(expectedRoots, sourceCount,
                "_count must report root documents only");
            Assertions.assertEquals(expectedRoots + expectedChildren, sourceDocsCount,
                "docs.count must include nested children -- if this fails the fixture is not "
                + "exercising nested documents and the rest of the test proves nothing");
            Assertions.assertTrue(sourceDocsCount > sourceCount,
                "the two counts must diverge for this test to be meaningful");

            // === Snapshot and migrate ===
            var snapshotName = "nested_snap";
            var sourceClientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(sourceCluster.getUrl())
                .insecure(true)
                .build()
                .toConnectionContext());
            var snapshotCreator = new SnapshotCreator(
                snapshotName,
                "nested_snap_repo",
                sourceClientFactory.determineVersionAndCreate(),
                RepoUri.parse(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR),
                List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            sourceCluster.copySnapshotData(localDirectory.toString());

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(sourceVersion, true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

            var runCounter = new AtomicInteger();
            waitForRfsCompletion(() -> migrateDocumentsSequentially(
                sourceRepo,
                snapshotName,
                List.of(),
                targetCluster,
                runCounter,
                new Random(1),
                testContext,
                sourceVersion,
                targetCluster.getContainerVersion().getVersion(),
                null
            ));

            // === The assertion this test exists for ===
            var targetOps = new ClusterOperations(targetCluster);
            targetOps.refresh();
            int targetCount = countViaCountApi(targetOps, NESTED_INDEX);

            Assertions.assertEquals(sourceCount, targetCount,
                "documents actually migrated must equal the source's live root-document count");
            Assertions.assertEquals(expectedRoots, targetCount,
                "nested children must not be migrated as separate documents");
        } finally {
            FileSystemUtils.deleteDirectories(localDirectory.toString());
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
