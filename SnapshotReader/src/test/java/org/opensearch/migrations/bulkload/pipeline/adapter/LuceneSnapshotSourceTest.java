package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LuceneSnapshotSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SNAPSHOT = "test-snap";
    private static final String INDEX = "test-index";

    @Mock private SnapshotExtractor extractor;
    @Mock private ClusterSnapshotReader snapshotReader;
    @Mock private IndexMetadata.Factory indexMetadataFactory;
    @Mock private IndexMetadata indexMetadata;
    @Mock private ShardMetadata shardMetadata;

    @TempDir Path workDir;

    private LuceneSnapshotSource source;

    @BeforeEach
    void setUp() {
        source = new LuceneSnapshotSource(extractor, SNAPSHOT, workDir);
    }

    @Test
    void listIndicesDelegatesToExtractor() {
        when(extractor.listIndices(SNAPSHOT)).thenReturn(List.of("idx-a", "idx-b"));

        var indices = source.listIndices();

        assertEquals(List.of("idx-a", "idx-b"), indices);
        verify(extractor).listIndices(SNAPSHOT);
    }

    @Test
    void listShardsConvertsToShardIds() {
        var entries = List.of(
            new SnapshotExtractor.ShardEntry(SNAPSHOT, INDEX, "idx-id", 0, shardMetadata),
            new SnapshotExtractor.ShardEntry(SNAPSHOT, INDEX, "idx-id", 1, shardMetadata)
        );
        when(extractor.listShards(SNAPSHOT, INDEX)).thenReturn(entries);

        var shards = source.listShards(INDEX);

        assertEquals(2, shards.size());
        assertEquals(new ShardId(SNAPSHOT, INDEX, 0), shards.get(0));
        assertEquals(new ShardId(SNAPSHOT, INDEX, 1), shards.get(1));
    }

    @Test
    void readIndexMetadataConvertsFromExisting() {
        ObjectNode mappings = MAPPER.createObjectNode().put("type", "keyword");
        ObjectNode settings = MAPPER.createObjectNode().put("number_of_replicas", 2);
        ObjectNode aliases = MAPPER.createObjectNode();

        when(extractor.getSnapshotReader()).thenReturn(snapshotReader);
        when(snapshotReader.getIndexMetadata()).thenReturn(indexMetadataFactory);
        when(indexMetadataFactory.fromRepo(SNAPSHOT, INDEX)).thenReturn(indexMetadata);
        when(indexMetadata.getNumberOfShards()).thenReturn(3);
        when(indexMetadata.getMappings()).thenReturn(mappings);
        when(indexMetadata.getSettings()).thenReturn(settings);
        when(indexMetadata.getAliases()).thenReturn(aliases);

        var meta = source.readIndexMetadata(INDEX);

        assertEquals(INDEX, meta.indexName());
        assertEquals(3, meta.numberOfShards());
        assertEquals(2, meta.numberOfReplicas());
        assertEquals(mappings, meta.mappings());
    }

    @Test
    void readDocumentsConvertsAndSkips() {
        var entry = new SnapshotExtractor.ShardEntry(SNAPSHOT, INDEX, "idx-id", 0, shardMetadata);
        when(extractor.listShards(SNAPSHOT, INDEX)).thenReturn(List.of(entry));

        var doc0 = new LuceneDocumentChange(0, "id-0", "_doc", "{\"f\":0}".getBytes(), null, DocumentChangeType.INDEX);
        var doc1 = new LuceneDocumentChange(1, "id-1", "_doc", "{\"f\":1}".getBytes(), null, DocumentChangeType.INDEX);
        var doc2 = new LuceneDocumentChange(2, "id-2", "_doc", "{\"f\":2}".getBytes(), null, DocumentChangeType.INDEX);
        when(extractor.readDocuments(eq(entry), any(Path.class))).thenReturn(Flux.just(doc0, doc1, doc2));

        // List shards first to populate cache
        source.listShards(INDEX);

        // Read with offset 1 should skip first doc
        var shardId = new ShardId(SNAPSHOT, INDEX, 0);
        StepVerifier.create(source.readDocuments(shardId, 1))
            .assertNext(doc -> {
                assertEquals("id-1", doc.id());
                assertEquals(DocumentChange.ChangeType.INDEX, doc.operation());
            })
            .assertNext(doc -> assertEquals("id-2", doc.id()))
            .verifyComplete();
    }

    @Test
    void readDocumentsPopulatesCacheOnMiss() {
        var entry = new SnapshotExtractor.ShardEntry(SNAPSHOT, INDEX, "idx-id", 0, shardMetadata);
        when(extractor.listShards(SNAPSHOT, INDEX)).thenReturn(List.of(entry));
        when(extractor.readDocuments(eq(entry), any(Path.class))).thenReturn(Flux.empty());

        // Don't call listShards first — readDocuments should auto-populate
        var shardId = new ShardId(SNAPSHOT, INDEX, 0);
        StepVerifier.create(source.readDocuments(shardId, 0))
            .verifyComplete();

        verify(extractor).listShards(SNAPSHOT, INDEX);
    }

    @Test
    void deltaModeDelegatesToReadDeltaDocuments() {
        var previousSnapshot = "prev-snap";
        @SuppressWarnings("unchecked")
        var deltaContextFactory = (java.util.function.Supplier<IRfsContexts.IDeltaStreamContext>) mock(java.util.function.Supplier.class);
        var deltaSource = new LuceneSnapshotSource(
            extractor, SNAPSHOT, workDir,
            previousSnapshot, DeltaMode.UPDATES_AND_DELETES, deltaContextFactory
        );

        assertTrue(deltaSource.isDeltaMode());

        var currentEntry = new SnapshotExtractor.ShardEntry(SNAPSHOT, INDEX, "idx-id", 0, shardMetadata);
        var previousEntry = new SnapshotExtractor.ShardEntry(previousSnapshot, INDEX, "idx-id", 0, shardMetadata);
        when(extractor.listShards(SNAPSHOT, INDEX)).thenReturn(List.of(currentEntry));
        when(extractor.listShards(previousSnapshot, INDEX)).thenReturn(List.of(previousEntry));

        var deletion = new LuceneDocumentChange(0, "del-1", "_doc", null, null, DocumentChangeType.DELETE);
        var addition = new LuceneDocumentChange(1, "add-1", "_doc", "{\"f\":1}".getBytes(), null, DocumentChangeType.INDEX);
        when(extractor.readDeltaDocuments(
            eq(currentEntry), eq(previousEntry), eq(DeltaMode.UPDATES_AND_DELETES),
            any(Path.class), eq(deltaContextFactory)
        )).thenReturn(Flux.just(deletion, addition));

        deltaSource.listShards(INDEX);
        var shardId = new ShardId(SNAPSHOT, INDEX, 0);

        StepVerifier.create(deltaSource.readDocuments(shardId, 0))
            .assertNext(doc -> {
                assertEquals("del-1", doc.id());
                assertEquals(DocumentChange.ChangeType.DELETE, doc.operation());
            })
            .assertNext(doc -> {
                assertEquals("add-1", doc.id());
                assertEquals(DocumentChange.ChangeType.INDEX, doc.operation());
            })
            .verifyComplete();

        verify(extractor).readDeltaDocuments(
            eq(currentEntry), eq(previousEntry), eq(DeltaMode.UPDATES_AND_DELETES),
            any(Path.class), eq(deltaContextFactory)
        );
    }

    @Test
    void deltaModeFallsBackToRegularWhenNoPreviousShard() {
        var previousSnapshot = "prev-snap";
        @SuppressWarnings("unchecked")
        var deltaContextFactory = (java.util.function.Supplier<IRfsContexts.IDeltaStreamContext>) mock(java.util.function.Supplier.class);
        var deltaSource = new LuceneSnapshotSource(
            extractor, SNAPSHOT, workDir,
            previousSnapshot, DeltaMode.UPDATES_AND_DELETES, deltaContextFactory
        );

        var currentEntry = new SnapshotExtractor.ShardEntry(SNAPSHOT, INDEX, "idx-id", 0, shardMetadata);
        when(extractor.listShards(SNAPSHOT, INDEX)).thenReturn(List.of(currentEntry));
        // Previous snapshot has no shards for this index
        when(extractor.listShards(previousSnapshot, INDEX)).thenReturn(List.of());

        var doc = new LuceneDocumentChange(0, "id-0", "_doc", "{\"f\":0}".getBytes(), null, DocumentChangeType.INDEX);
        when(extractor.readDocuments(eq(currentEntry), any(Path.class))).thenReturn(Flux.just(doc));

        deltaSource.listShards(INDEX);
        var shardId = new ShardId(SNAPSHOT, INDEX, 0);

        StepVerifier.create(deltaSource.readDocuments(shardId, 0))
            .assertNext(d -> {
                assertEquals("id-0", d.id());
                assertEquals(DocumentChange.ChangeType.INDEX, d.operation());
            })
            .verifyComplete();

        // Should NOT call readDeltaDocuments — falls back to regular read
        verify(extractor, never()).readDeltaDocuments(any(), any(), any(), any(), any());
        verify(extractor).readDocuments(eq(currentEntry), any(Path.class));
    }
}
