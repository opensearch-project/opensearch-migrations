package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.framework.SnapshotFixtureCache;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Focused spike smoke test exercising the full SnapshotReader pipeline against
 * real ES 9.x and OS 3.x containers. Narrower than LuceneSnapshotSourceEndToEndTest
 * (which iterates every supported source) so we can iterate on Lucene 10 /
 * shard_path_type issues without waiting for 20+ irrelevant container versions.
 *
 * Test matrix:
 *   - ES 9.1  (Lucene 10, ES default repo layout)
 *   - OS 3.5  (Lucene 10, OS HASHED_PREFIX-capable repo layout)
 */
@Slf4j
@Tag("isolatedTest")
public class Lucene10Es9Os3SnapshotSpikeTest {

    private static final String SNAPSHOT_NAME = "test_snapshot";
    private static final String REPO_NAME = "test_repo";
    private static final String INDEX_NAME = "spike_source_test";

    @TempDir private File localDirectory;

    private static final SnapshotFixtureCache fixtureCache = new SnapshotFixtureCache();

    static Stream<Arguments> lucene10Sources() {
        return Stream.of(
            Arguments.of(SearchClusterContainer.ES_V9_1),
            Arguments.of(SearchClusterContainer.OS_V3_5_0)
        );
    }

    private SnapshotExtractor createSnapshot(ContainerVersion sourceVersion) throws Exception {
        String cacheKey = sourceVersion.getVersion() + "-pipeline-source";
        Path snapshotDir = localDirectory.toPath();

        if (fixtureCache.restoreIfCached(cacheKey, snapshotDir)) {
            return SnapshotExtractor.forLocalSnapshot(snapshotDir, sourceVersion.getVersion());
        }

        try (var cluster = new SearchClusterContainer(sourceVersion)) {
            cluster.start();
            var ops = new ClusterOperations(cluster);

            ops.createIndex(INDEX_NAME, "{"
                + "\"settings\": {"
                + "  \"number_of_shards\": 1,"
                + "  \"number_of_replicas\": 0"
                + "}"
                + "}");
            ops.createDocument(INDEX_NAME, "doc1", "{\"title\": \"First\", \"value\": 1}");
            ops.createDocument(INDEX_NAME, "doc2", "{\"title\": \"Second\", \"value\": 2}");
            ops.createDocument(INDEX_NAME, "doc3", "{\"title\": \"Third\", \"value\": 3}");
            ops.post("/" + INDEX_NAME + "/_refresh", null);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
                .host(cluster.getUrl()).insecure(true).build().toConnectionContext());
            var snapshotCreator = new FileSystemSnapshotCreator(
                SNAPSHOT_NAME, REPO_NAME, clientFactory.determineVersionAndCreate(),
                SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, List.of(),
                snapshotContext.createSnapshotCreateContext()
            );
            SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            cluster.copySnapshotData(localDirectory.toString());
            fixtureCache.store(cacheKey, snapshotDir);
        }

        return SnapshotExtractor.forLocalSnapshot(snapshotDir, sourceVersion.getVersion());
    }

    @ParameterizedTest(name = "listCollections from {0}")
    @MethodSource("lucene10Sources")
    void listCollectionsFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT_NAME, localDirectory.toPath()).build();

        var collections = source.listCollections();

        assertThat("Should contain our test index", collections.contains(INDEX_NAME), equalTo(true));
        log.info("Listed {} collections from {} snapshot", collections.size(), sourceVersion);
    }

    @ParameterizedTest(name = "readDocuments from {0}")
    @MethodSource("lucene10Sources")
    void readDocumentsFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        Path workDir = Files.createTempDirectory("spike_source_reads");
        try {
            var source = LuceneSnapshotSource.builder(extractor, SNAPSHOT_NAME, workDir).build();
            source.listPartitions(INDEX_NAME);

            var partition = source.listPartitions(INDEX_NAME).get(0);
            var docs = source.readDocuments(partition, 0).collectList().block();

            assertThat("Should read 3 documents", docs, hasSize(3));
            var ids = docs.stream().map(Document::id).sorted().toList();
            assertThat(ids, equalTo(List.of("doc1", "doc2", "doc3")));

            for (var doc : docs) {
                assertThat(doc.source(), notNullValue());
                assertThat(doc.source().length, greaterThan(0));
                assertThat(doc.operation(), equalTo(Document.Operation.UPSERT));
            }
            log.info("Read {} documents from {} snapshot via LuceneSnapshotSource", docs.size(), sourceVersion);
        } finally {
            deleteDir(workDir);
        }
    }

    @ParameterizedTest(name = "readGlobalMetadata from {0}")
    @MethodSource("lucene10Sources")
    void readGlobalMetadataFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = new SnapshotMetadataSource(extractor, SNAPSHOT_NAME);

        var globalMeta = source.readGlobalMetadata();

        assertThat(globalMeta, notNullValue());
        assertThat("Should list our index", globalMeta.indices().contains(INDEX_NAME), equalTo(true));
    }

    @ParameterizedTest(name = "readIndexMetadata via metadata source from {0}")
    @MethodSource("lucene10Sources")
    void readIndexMetadataViaMetadataSource(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = new SnapshotMetadataSource(extractor, SNAPSHOT_NAME);

        var indexMeta = source.readIndexMetadata(INDEX_NAME);

        assertThat(indexMeta.indexName(), equalTo(INDEX_NAME));
        assertThat(indexMeta.numberOfShards(), equalTo(1));
    }

    private static void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean up: {}", dir, e);
        }
    }
}
