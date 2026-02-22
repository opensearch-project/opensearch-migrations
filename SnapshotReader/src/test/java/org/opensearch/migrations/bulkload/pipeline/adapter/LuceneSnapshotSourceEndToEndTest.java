package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.framework.SnapshotFixtureCache;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Source-side e2e tests for the pipeline adapters: {@link LuceneSnapshotSource} and
 * {@link SnapshotMetadataSource} against real snapshots from Docker containers.
 *
 * <p>No target cluster needed — validates the N side of the N+M testing strategy.
 */
@Slf4j
public class LuceneSnapshotSourceEndToEndTest {

    private static final String SNAPSHOT_NAME = "test_snapshot";
    private static final String REPO_NAME = "test_repo";
    private static final String INDEX_NAME = "pipeline_source_test";

    @TempDir private File localDirectory;

    private static final SnapshotFixtureCache fixtureCache = new SnapshotFixtureCache();

    static Stream<Arguments> supportedSources() {
        return SupportedClusters.supportedSources(false).stream().map(Arguments::of);
    }

    // --- Helpers ---

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

    // --- Source adapter tests ---

    @ParameterizedTest(name = "listIndices from {0}")
    @MethodSource("supportedSources")
    void listIndicesFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, localDirectory.toPath());

        var indices = source.listIndices();

        assertThat("Should contain our test index", indices.contains(INDEX_NAME), equalTo(true));
        log.info("Listed {} indices from {} snapshot", indices.size(), sourceVersion);
    }

    @ParameterizedTest(name = "listShards from {0}")
    @MethodSource("supportedSources")
    void listShardsFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, localDirectory.toPath());

        var shards = source.listShards(INDEX_NAME);

        assertThat("Should have 1 shard", shards, hasSize(1));
        assertThat(shards.get(0).snapshotName(), equalTo(SNAPSHOT_NAME));
        assertThat(shards.get(0).indexName(), equalTo(INDEX_NAME));
        assertThat(shards.get(0).shardNumber(), equalTo(0));
    }

    @ParameterizedTest(name = "readIndexMetadata from {0}")
    @MethodSource("supportedSources")
    void readIndexMetadataFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, localDirectory.toPath());

        var metadata = source.readIndexMetadata(INDEX_NAME);

        assertThat(metadata.indexName(), equalTo(INDEX_NAME));
        assertThat(metadata.numberOfShards(), equalTo(1));
        // Settings/mappings may be null for versions where the underlying reader returns non-ObjectNode types
    }

    @ParameterizedTest(name = "readDocuments from {0}")
    @MethodSource("supportedSources")
    void readDocumentsFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        Path workDir = Files.createTempDirectory("pipeline_source_e2e");
        try {
            var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, workDir);
            source.listShards(INDEX_NAME); // populate cache

            var shardId = source.listShards(INDEX_NAME).get(0);
            var docs = source.readDocuments(shardId, 0).collectList().block();

            assertThat("Should read 3 documents", docs, hasSize(3));
            var ids = docs.stream().map(DocumentChange::id).sorted().toList();
            assertThat(ids, equalTo(List.of("doc1", "doc2", "doc3")));

            for (var doc : docs) {
                assertThat(doc.source(), notNullValue());
                assertThat(doc.source().length, greaterThan(0));
                assertThat(doc.operation(), equalTo(DocumentChange.ChangeType.INDEX));
            }
            log.info("Read {} documents from {} snapshot via LuceneSnapshotSource", docs.size(), sourceVersion);
        } finally {
            deleteDir(workDir);
        }
    }

    @ParameterizedTest(name = "resume from offset on {0}")
    @MethodSource("supportedSources")
    void resumeFromOffsetOnRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        Path workDir = Files.createTempDirectory("pipeline_source_resume");
        try {
            var source = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, workDir);
            var shardId = source.listShards(INDEX_NAME).get(0);

            // Read all 3 docs
            var allDocs = source.readDocuments(shardId, 0).collectList().block();
            assertThat(allDocs, hasSize(3));

            // Resume from offset 2 — should get only the last doc (use fresh source to avoid unpack conflict)
            Path workDir2 = Files.createTempDirectory("pipeline_source_resume2");
            try {
                var source2 = new LuceneSnapshotSource(extractor, SNAPSHOT_NAME, workDir2);
                source2.listShards(INDEX_NAME);
                var resumed = source2.readDocuments(shardId, 2).collectList().block();
                assertThat("Resuming from offset 2 should yield 1 doc", resumed, hasSize(1));
                assertThat(resumed.get(0).id(), equalTo(allDocs.get(2).id()));
            } finally {
                deleteDir(workDir2);
            }
        } finally {
            deleteDir(workDir);
        }
    }

    // --- Metadata source adapter tests ---

    @ParameterizedTest(name = "readGlobalMetadata from {0}")
    @MethodSource("supportedSources")
    void readGlobalMetadataFromRealSnapshot(ContainerVersion sourceVersion) throws Exception {
        var extractor = createSnapshot(sourceVersion);
        var source = new SnapshotMetadataSource(extractor, SNAPSHOT_NAME);

        var globalMeta = source.readGlobalMetadata();

        assertNotNull(globalMeta, "GlobalMetadataSnapshot should not be null");
        assertThat("Should list our index", globalMeta.indices().contains(INDEX_NAME), equalTo(true));
    }

    @ParameterizedTest(name = "readIndexMetadata via metadata source from {0}")
    @MethodSource("supportedSources")
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
