package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationBootstrap;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.utils.FileSystemUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;

/** Validates a full backfill against the snapshot baseline while the live source keeps changing. */
@Slf4j
@Tag("isolatedTest")
public class SnapshotDocCountTest extends SourceTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NESTED_INDEX = "nested_docs";
    private static final int SHARDS = 2;

    @TempDir
    private File localDirectory;

    @Test
    @SneakyThrows
    void legacyUserFieldIsNotNestedMetadata() {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)) {
            cluster.start();
            var operations = new ClusterOperations(cluster);
            operations.createIndex("legacy", "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},"
                + "\"mappings\":{\"properties\":{\"_nested_path\":{\"type\":\"keyword\"}}}}");
            operations.createDocument("legacy", "root", "{\"_nested_path\":\"ordinary user data\"}");
            operations.refresh("legacy");
            var client = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(cluster.getUrl()).insecure(true).build().toConnectionContext()).determineVersionAndCreate();
            var creator = new SnapshotCreator("legacy-snapshot", "legacy-repo", client,
                RepoUri.parse(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR), List.of(),
                SnapshotTestContext.factory().noOtelTracking().createSnapshotCreateContext());
            SnapshotRunner.runAndWaitForCompletion(creator);
            cluster.copySnapshotData(localDirectory.toString());
            var extractor = SnapshotExtractor.forLocalSnapshot(
                localDirectory.toPath(), cluster.getContainerVersion().getVersion());
            try (var source = LuceneSnapshotSource.builder(extractor, "legacy-snapshot",
                    Files.createTempDirectory(localDirectory.toPath(), "counts")).build()) {
                var partition = source.listPartitions("legacy").get(0);
                Assertions.assertEquals(1, source.readDocuments(partition, null).count().block());
                Assertions.assertEquals(new SnapshotDocumentCounts(1, 0, 0),
                    source.snapshotDocumentCounts(partition).orElseThrow());
            }
        }
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_4),
            Arguments.of(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "Source {0} to Target {1}")
    @MethodSource("scenarios")
    public void snapshotBaselineMatchesTargetWhileSourceContinuesIngesting(
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
            var targetOps = new ClusterOperations(targetCluster);
            var sourceVersion = sourceCluster.getContainerVersion().getVersion();
            String properties =
                "    \"title\": {\"type\": \"text\"}," +
                "    \"answers\": {" +
                "      \"type\": \"nested\"," +
                "      \"properties\": {" +
                "        \"user\": {\"type\": \"keyword\"}," +
                "        \"score\": {\"type\": \"integer\"}" +
                "      }" +
                "    }";
            String mappings = "{\"properties\": {" + properties + "}}";

            sourceOps.createIndex(NESTED_INDEX,
                "{" +
                "  \"settings\": {\"number_of_shards\": " + SHARDS + ", \"number_of_replicas\": 0}," +
                "  \"mappings\": " + mappings +
                "}");

            // Preserve nested mappings, as metadata migration would. Dynamic object mappings
            // would produce a different Lucene count even with identical root documents.
            targetOps.createIndex(NESTED_INDEX,
                "{\"settings\":{\"number_of_shards\":" + SHARDS + ",\"number_of_replicas\":0},"
                + "\"mappings\":{\"properties\":{" + properties + "}}}");

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

            sourceOps.createDocument(NESTED_INDEX, "root0",
                "{\"title\":\"doc 0 updated\",\"answers\":[{\"user\":\"z\",\"score\":9}]}");
            sourceOps.refresh(NESTED_INDEX);
            expectedChildren = expectedChildren - childCounts[0] + 1;

            int expectedRoots = childCounts.length;

            int sourceCount = countViaCountApi(sourceOps, NESTED_INDEX);
            int sourceDocsCount = docsCountViaCat(sourceOps, NESTED_INDEX);
            log.info("source _count={}, source docs.count={}, expectedRoots={}, expectedChildren={}",
                sourceCount, sourceDocsCount, expectedRoots, expectedChildren);

            Assertions.assertEquals(expectedRoots, sourceCount,
                "_count must report root documents only");
            Assertions.assertEquals(expectedRoots + expectedChildren, sourceDocsCount,
                "docs.count must include nested children -- if this fails the fixture is not "
                + "exercising nested documents and the rest of the test proves nothing");
            Assertions.assertTrue(sourceDocsCount > sourceCount,
                "the two counts must diverge for this test to be meaningful");

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

            sourceOps.createDocument(NESTED_INDEX, "after-snapshot",
                "{\"title\":\"new\",\"answers\":[{\"user\":\"later\",\"score\":1}]}");
            sourceOps.refresh(NESTED_INDEX);
            Assertions.assertTrue(docsCountViaCat(sourceOps, NESTED_INDEX) > sourceDocsCount);

            var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(sourceVersion, true);
            var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);

            var extractor = SnapshotExtractor.create(sourceVersion,
                SnapshotReaderRegistry.getSnapshotReader(sourceVersion, sourceRepo, false), sourceRepo);
            try (var source = LuceneSnapshotSource.builder(extractor, snapshotName,
                    Files.createTempDirectory(localDirectory.toPath(), "snapshot-counts")).build()) {
                long live = 0;
                long nested = 0;
                for (var partition : source.listPartitions(NESTED_INDEX)) {
                    source.readDocuments(partition, null).blockLast();
                    var counts = source.snapshotDocumentCounts(partition).orElseThrow();
                    live += counts.liveDocuments();
                    nested += counts.nestedDocuments();
                }
                Assertions.assertEquals(sourceDocsCount, live);
                Assertions.assertEquals(expectedChildren, nested);
            }

            var runCounter = new AtomicInteger();
            try (var logs = new CloseableLogSetup(DocumentMigrationBootstrap.class.getName())) {
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
                var countsByShard = new HashMap<Integer, Long>();
                var pattern = Pattern.compile("Partition migration stats: index=" + NESTED_INDEX
                    + ", partition=[^,]*/(\\d+),.*shardLiveLuceneDocs=(\\d+)");
                logs.getLogEvents().forEach(line -> {
                    var match = pattern.matcher(line);
                    if (match.find()) {
                        var shard = Integer.parseInt(match.group(1));
                        var count = Long.parseLong(match.group(2));
                        var previous = countsByShard.putIfAbsent(shard, count);
                        if (previous != null) {
                            Assertions.assertEquals(previous.longValue(), count,
                                "retry records must describe the same snapshot shard");
                        }
                    }
                });
                Assertions.assertEquals(SHARDS, countsByShard.size(), "every snapshot shard needs a count");
                Assertions.assertEquals(sourceDocsCount,
                    countsByShard.values().stream().mapToLong(Long::longValue).sum(),
                    "sum one count per shard to recover the snapshot's index count");
            }

            targetOps.refresh();
            int targetCount = countViaCountApi(targetOps, NESTED_INDEX);

            Assertions.assertEquals(sourceDocsCount, docsCountViaCat(targetOps, NESTED_INDEX),
                "the target's docs.count must match the snapshot baseline, including nested children");
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
    private static int docsCountViaCat(ClusterOperations ops, String index) {
        var response = ops.get("/_cat/indices/" + index + "?format=json&h=index,docs.count");
        Assertions.assertEquals(200, response.getKey(), "unexpected _cat/indices response: " + response.getValue());
        return MAPPER.readTree(response.getValue()).get(0).get("docs.count").asInt();
    }
}
