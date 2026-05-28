package org.opensearch.migrations.bulkload.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentReaderEngine.DocumentChangeset;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.SnapshotShardUnpacker;
import org.opensearch.migrations.bulkload.common.SourceRepoAccessor;
import org.opensearch.migrations.bulkload.common.TestResources;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Real-fixtures test (no mocks) for RegularDocumentReaderEngine.
 * Drives prepareChangeset() over an actual ES 6.8 snapshot fixture so that
 * line 106 of RegularDocumentReaderEngine.java
 * (LuceneReader.readDocsByLeavesFromStartingPosition(directoryReader,
 *  startingDocId, mappingContext, false)) is executed end-to-end.
 */
@Slf4j
public class RegularDocumentReaderEngineTest {

    private static final String INDEX_NAME = "test_updates_deletes";
    private static final int SHARD_ID = 0;

    private Path tempDirectory;

    @BeforeEach
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("regular-doc-reader-engine-test");
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (tempDirectory != null && Files.exists(tempDirectory)) {
            try (var walk = Files.walk(tempDirectory)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.atWarn().setCause(e).setMessage("Failed to delete {}").addArgument(p).log();
                        }
                    });
            }
        }
    }

    @Test
    public void prepareChangeset_streamsAdditionsFromRealSnapshot() throws IOException {
        // Real ES 6.8 snapshot fixture; no mocking anywhere.
        TestResources.Snapshot snapshot = TestResources.SNAPSHOT_ES_6_8;
        Version version = Version.fromString("ES 6.8");

        var fileFinder = SnapshotReaderRegistry.getSnapshotFileFinder(version, true);
        var repo = new FileSystemRepo(snapshot.dir, fileFinder);
        var snapshotReader = SnapshotReaderRegistry.getSnapshotReader(version, repo, false);
        var repoAccessor = new SourceRepoAccessor(repo);

        var shardMetadataFactory = snapshotReader.getShardMetadata();
        var indexMetadataFactory = snapshotReader.getIndexMetadata();

        // Build the engine the same way production code does, including the
        // indexMetadataFactory + snapshotName so getFieldMappingContext exercises
        // the real cache path as well.
        var engine = new RegularDocumentReaderEngine(
            (idx, shard) -> shardMetadataFactory.fromRepo(snapshot.name, idx, shard),
            indexMetadataFactory,
            snapshot.name
        );

        // 1. Use the engine to create the unpacker (covers createUnpacker code path).
        var unpackerFactory = new SnapshotShardUnpacker.Factory(repoAccessor, tempDirectory);
        SnapshotShardUnpacker unpacker = engine.createUnpacker(unpackerFactory, INDEX_NAME, SHARD_ID);
        Path luceneDir = unpacker.unpack();

        // 2. Build a real LuceneIndexReader rooted at the unpacked shard directory.
        LuceneIndexReader luceneIndexReader = new LuceneIndexReader.Factory(snapshotReader).getReader(luceneDir);

        // 3. Call prepareChangeset, which is the method whose body contains line 106:
        //    LuceneReader.readDocsByLeavesFromStartingPosition(directoryReader, startingDocId,
        //                                                      mappingContext, false)
        DocumentChangeset changeset = engine.prepareChangeset(luceneIndexReader, INDEX_NAME, SHARD_ID, 0);

        try {
            assertNotNull(changeset, "Changeset must not be null");
            assertNotNull(changeset.deletions(), "Deletions Flux must not be null");
            assertNotNull(changeset.additions(), "Additions Flux must not be null");
            assertNotNull(changeset.cleanup(), "Cleanup runnable must not be null");

            // RegularDocumentReaderEngine always emits an empty deletions Flux.
            StepVerifier.create(changeset.deletions()).verifyComplete();

            // Drain additions to actually run the read pipeline returned by line 106.
            // ES 6.8 fixture contains 3 live docs in test_updates_deletes/0.
            Flux<LuceneDocumentChange> additions = changeset.additions();
            Long count = additions.count().block();
            assertNotNull(count, "Additions count must not be null");
            org.junit.jupiter.api.Assertions.assertEquals(3L, count.longValue(),
                "Expected 3 live documents from ES 6.8 test_updates_deletes shard 0");

            // 4. Exercise the cached-mapping-context branch on the second call:
            //    same indexName must return the exact same FieldMappingContext instance.
            var ctx1 = engine.getFieldMappingContext(INDEX_NAME);
            var ctx2 = engine.getFieldMappingContext(INDEX_NAME);
            assertSame(ctx1, ctx2, "Mapping context should be cached for the same index name");
        } finally {
            // Run the cleanup hook returned alongside the changeset.
            changeset.cleanup().run();
        }
    }
}
