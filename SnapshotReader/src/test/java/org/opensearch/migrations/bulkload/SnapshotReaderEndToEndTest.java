package org.opensearch.migrations.bulkload;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.framework.SnapshotFixtureCache;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.worker.RegularDocumentReaderEngine;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end tests for the snapshot reading pipeline.
 * Tests source-side only: create snapshot → read → validate IR output.
 * No target cluster needed.
 */
@Slf4j
@Tag("isolatedTest")
public class SnapshotReaderEndToEndTest {

    private static final String SNAPSHOT_NAME = "test_snapshot";
    private static final String REPO_NAME = "test_repo";
    private static final String INDEX_NAME = "test_index";

    @TempDir
    private File localDirectory;

    private static final SnapshotFixtureCache fixtureCache = new SnapshotFixtureCache();

    static Stream<Arguments> supportedSources() {
        return SupportedClusters.supportedSources(false).stream()
            .map(Arguments::of);
    }

    /** All minor versions: sources + extendedSources, deduplicated. */
    static Stream<Arguments> allSources() {
        return Stream.concat(
            SupportedClusters.supportedSources(false).stream(),
            SupportedClusters.extendedSources().stream()
        ).distinct().map(Arguments::of);
    }

    // --- Helpers ---

    private record SnapshotOnDisk(
        ClusterSnapshotReader reader,
        FileSystemRepo sourceRepo,
        Version version
    ) {}

    /**
     * Returns a cached snapshot if available, otherwise starts a container,
     * runs the data setup, creates a snapshot, caches it, and returns the reader.
     */
    private SnapshotOnDisk getOrCreateSnapshot(
        ContainerVersion sourceVersion,
        String scenario,
        Consumer<ClusterOperations> dataSetup
    ) throws Exception {
        String cacheKey = sourceVersion.getVersion() + "-" + scenario;
        Path snapshotDir = localDirectory.toPath();

        if (fixtureCache.restoreIfCached(cacheKey, snapshotDir)) {
            return createReaderFromDir(snapshotDir, sourceVersion);
        }

        try (var sourceCluster = new SearchClusterContainer(sourceVersion)) {
            sourceCluster.start();
            dataSetup.accept(new ClusterOperations(sourceCluster));
            var snapshot = createAndReadSnapshot(sourceCluster);
            fixtureCache.store(cacheKey, snapshotDir);
            return snapshot;
        }
    }

    private SnapshotOnDisk createReaderFromDir(Path dir, ContainerVersion sourceVersion) {
        Version version = sourceVersion.getVersion();
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        var sourceRepo = new FileSystemRepo(dir, fileFinder);
        var reader = SnapshotReaderRegistry.getSnapshotReader(version, sourceRepo, true);
        return new SnapshotOnDisk(reader, sourceRepo, version);
    }

    /**
     * Creates a snapshot from the running cluster and returns the reader.
     */
    private SnapshotOnDisk createAndReadSnapshot(SearchClusterContainer cluster) throws Exception {
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var clientFactory = new OpenSearchClientFactory(ConnectionContextTestParams.builder()
            .host(cluster.getUrl())
            .insecure(true)
            .build()
            .toConnectionContext());
        var snapshotCreator = new FileSystemSnapshotCreator(
            SNAPSHOT_NAME, REPO_NAME, clientFactory.determineVersionAndCreate(),
            SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, List.of(),
            snapshotContext.createSnapshotCreateContext()
        );
        SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
        cluster.copySnapshotData(localDirectory.toString());

        Version version = cluster.getContainerVersion().getVersion();
        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        var sourceRepo = new FileSystemRepo(localDirectory.toPath(), fileFinder);
        var reader = SnapshotReaderRegistry.getSnapshotReader(version, sourceRepo, true);
        return new SnapshotOnDisk(reader, sourceRepo, version);
    }

    /**
     * Unpacks a shard and reads all documents as a Flux.
     */
    private Flux<LuceneDocumentChange> readDocumentsFromShard(
        ClusterSnapshotReader snapshotReader,
        FileSystemRepo sourceRepo,
        Path luceneDir,
        String indexName,
        int shardId
    ) {
        var repoAccessor = new SourceRepoAccessor(sourceRepo);
        var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, luceneDir);
        var readerFactory = new LuceneIndexReader.Factory(snapshotReader);

        var engine = new RegularDocumentReaderEngine(
            (idx, shard) -> snapshotReader.getShardMetadata().fromRepo(SNAPSHOT_NAME, idx, shard)
        );

        // Unpack shard files to local Lucene directory
        engine.createUnpacker(unpackerFactory, indexName, shardId).unpack();

        ShardMetadata shardMeta = snapshotReader.getShardMetadata().fromRepo(SNAPSHOT_NAME, indexName, shardId);
        Path shardPath = luceneDir.resolve(indexName).resolve(String.valueOf(shardId));
        LuceneIndexReader indexReader = readerFactory.getReader(shardPath);
        return indexReader.streamDocumentChanges(shardMeta.getSegmentFileName());
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
            log.warn("Failed to clean up temp dir: {}", dir, e);
        }
    }

    // --- Tests ---

    /**
     * Validates the full document reading pipeline:
     * source cluster → snapshot → local FS → ClusterSnapshotReader → Flux<LuceneDocumentChange>
     */
    @ParameterizedTest(name = "Document IR from {0}")
    @MethodSource("allSources")
    void snapshotProducesCorrectDocumentIR(ContainerVersion sourceVersion) throws Exception {
        var snapshot = getOrCreateSnapshot(sourceVersion, "docIR", ops -> {
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
        });

        Path luceneDir = Files.createTempDirectory("snapshot_reader_test_docs");
        try {
            Flux<LuceneDocumentChange> docFlux = readDocumentsFromShard(
                snapshot.reader(), snapshot.sourceRepo(), luceneDir, INDEX_NAME, 0
            );

            List<LuceneDocumentChange> docs = new ArrayList<>();
            StepVerifier.create(docFlux)
                .thenConsumeWhile(doc -> {
                    docs.add(doc);
                    return true;
                })
                .verifyComplete();

            assertThat("Expected 3 documents from snapshot", docs, hasSize(3));

            var docIds = docs.stream().map(LuceneDocumentChange::getId).sorted().toList();
            assertThat(docIds, equalTo(List.of("doc1", "doc2", "doc3")));

            for (var doc : docs) {
                assertThat("Document " + doc.getId() + " should have source",
                    doc.getSource(), notNullValue());
                assertThat("Document " + doc.getId() + " should have non-empty source",
                    doc.getSource().length, greaterThan(0));
            }

            log.info("Successfully read {} documents from {} snapshot", docs.size(), sourceVersion);
        } finally {
            deleteDir(luceneDir);
        }
    }

    /**
     * Validates metadata IR: GlobalMetadata and IndexMetadata from snapshot.
     */
    @ParameterizedTest(name = "Metadata IR from {0}")
    @MethodSource("supportedSources")
    void snapshotProducesCorrectMetadataIR(ContainerVersion sourceVersion) throws Exception {
        var snapshot = getOrCreateSnapshot(sourceVersion, "metadataIR", ops -> {
            ops.createIndex(INDEX_NAME, "{"
                + "\"settings\": {"
                + "  \"number_of_shards\": 2,"
                + "  \"number_of_replicas\": 0"
                + "}"
                + "}");
            ops.createDocument(INDEX_NAME, "1", "{\"field\": \"value\"}");
            ops.post("/" + INDEX_NAME + "/_refresh", null);
        });

        // Validate GlobalMetadata
        GlobalMetadata globalMetadata = snapshot.reader().getGlobalMetadata().fromRepo(SNAPSHOT_NAME);
        assertNotNull(globalMetadata, "GlobalMetadata should not be null");
        assertNotNull(globalMetadata.toObjectNode(), "GlobalMetadata JSON should not be null");

        // Validate IndexMetadata
        IndexMetadata indexMetadata = snapshot.reader().getIndexMetadata().fromRepo(SNAPSHOT_NAME, INDEX_NAME);
        assertNotNull(indexMetadata, "IndexMetadata should not be null");
        assertThat("Index should have 2 shards", indexMetadata.getNumberOfShards(), equalTo(2));
        assertNotNull(indexMetadata.getSettings(), "Settings should not be null");

        // Validate ShardMetadata for each shard
        for (int shard = 0; shard < indexMetadata.getNumberOfShards(); shard++) {
            ShardMetadata shardMeta = snapshot.reader().getShardMetadata()
                .fromRepo(SNAPSHOT_NAME, INDEX_NAME, shard);
            assertNotNull(shardMeta, "ShardMetadata for shard " + shard + " should not be null");
            assertThat("Shard should have files",
                shardMeta.getFiles().size(), greaterThanOrEqualTo(1));
            assertNotNull(shardMeta.getSegmentFileName(),
                "Segment file name should not be null");
        }

        log.info("Successfully validated metadata IR from {} snapshot", sourceVersion);
    }

    /**
     * Validates the Flux pipeline handles deleted documents correctly.
     * Creates docs, deletes some, takes snapshot, verifies only live docs appear.
     */
    @ParameterizedTest(name = "Deleted docs filtered from {0}")
    @MethodSource("supportedSources")
    void snapshotFiltersDeletedDocuments(ContainerVersion sourceVersion) throws Exception {
        var snapshot = getOrCreateSnapshot(sourceVersion, "deletedDocs", ops -> {
            ops.createIndex(INDEX_NAME, "{"
                + "\"settings\": {"
                + "  \"number_of_shards\": 1,"
                + "  \"number_of_replicas\": 0"
                + "}"
                + "}");

            ops.createDocument(INDEX_NAME, "keep1", "{\"status\": \"active\"}");
            ops.createDocument(INDEX_NAME, "keep2", "{\"status\": \"active\"}");
            ops.createDocument(INDEX_NAME, "keep3", "{\"status\": \"active\"}");
            ops.post("/" + INDEX_NAME + "/_refresh", null);

            ops.createDocument(INDEX_NAME, "toDelete", "{\"status\": \"deleted\"}");
            ops.createDocument(INDEX_NAME, "keep4", "{\"status\": \"active\"}");
            ops.post("/" + INDEX_NAME + "/_refresh", null);
            ops.deleteDocument(INDEX_NAME, "toDelete", null, null);
            ops.post("/" + INDEX_NAME + "/_refresh", null);
        });

        Path luceneDir = Files.createTempDirectory("snapshot_reader_test_deleted");
        try {
            Flux<LuceneDocumentChange> docFlux = readDocumentsFromShard(
                snapshot.reader(), snapshot.sourceRepo(), luceneDir, INDEX_NAME, 0
            );

            List<String> docIds = new ArrayList<>();
            StepVerifier.create(docFlux)
                .thenConsumeWhile(doc -> {
                    docIds.add(doc.getId());
                    return true;
                })
                .verifyComplete();

            assertThat("Should have 4 live documents", docIds, hasSize(4));
            assertThat("Deleted doc should not be present",
                docIds.stream().noneMatch(id -> id.equals("toDelete")), equalTo(true));
            assertThat("All keep docs should be present",
                docIds.stream().filter(id -> id.startsWith("keep")).count(), equalTo(4L));

            log.info("Successfully verified deleted doc filtering from {} ({} live docs)",
                sourceVersion, docIds.size());
        } finally {
            deleteDir(luceneDir);
        }
    }
}
