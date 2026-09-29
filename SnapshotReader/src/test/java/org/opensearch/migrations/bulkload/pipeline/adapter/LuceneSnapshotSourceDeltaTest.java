package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LuceneSnapshotSourceDeltaTest {
    private static final Path WORK_DIR = Path.of("work");
    private final SnapshotExtractor extractor = mock(SnapshotExtractor.class);

    @BeforeEach
    void snapshotsExist() {
        when(extractor.listSnapshots()).thenReturn(List.of("previous", "current"));
    }

    private LuceneSnapshotSource source(DeltaMode mode) {
        return LuceneSnapshotSource.builder(extractor, "current", WORK_DIR)
            .delta("previous", mode, () -> mock(IRfsContexts.IDeltaStreamContext.class))
            .emitDocType(true)
            .build();
    }

    private SnapshotExtractor.ShardEntry shard(String snapshot, String index, int number) {
        return new SnapshotExtractor.ShardEntry(snapshot, index, "id-" + index, number, mock(ShardMetadata.class));
    }

    @Test
    void listsTheUnionOfIndicesAndShardsInStableOrder() {
        when(extractor.listIndices("previous")).thenReturn(List.of("removed", "shared"));
        when(extractor.listIndices("current")).thenReturn(List.of("shared", "added"));
        when(extractor.listShards("previous", "shared"))
            .thenReturn(List.of(shard("previous", "shared", 0), shard("previous", "shared", 1)));
        when(extractor.listShards("current", "shared")).thenReturn(List.of(shard("current", "shared", 0)));
        var source = source(DeltaMode.DELETES_ONLY);

        assertEquals(List.of("added", "removed", "shared"), source.listCollections());
        assertEquals(List.of(new EsShardPartition("current", "shared", 0),
            new EsShardPartition("current", "shared", 1)), source.listPartitions("shared"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesNumericShardPositionsForPersistedWorkItems(boolean delta) {
        when(extractor.listIndices("current")).thenReturn(List.of("shared"));
        when(extractor.listShards("current", "shared")).thenReturn(
            IntStream.range(0, 12).mapToObj(i -> shard("current", "shared", i)).toList());
        if (delta) {
            when(extractor.listIndices("previous")).thenReturn(List.of("shared"));
            when(extractor.listShards("previous", "shared")).thenReturn(
                IntStream.range(0, 15).mapToObj(i -> shard("previous", "shared", i)).toList());
        }
        var source = delta ? source(DeltaMode.UPDATES_ONLY)
            : LuceneSnapshotSource.builder(extractor, "current", WORK_DIR).build();

        assertEquals(IntStream.range(0, delta ? 15 : 12)
            .mapToObj(i -> new EsShardPartition("current", "shared", i)).toList(), source.listPartitions("shared"));
    }

    @ParameterizedTest
    @EnumSource(DeltaMode.class)
    void newIndicesOnlyEmitAdditions(DeltaMode mode) {
        when(extractor.listIndices("previous")).thenReturn(List.of());
        when(extractor.listIndices("current")).thenReturn(List.of("added"));
        var entry = shard("current", "added", 0);
        when(extractor.listShards("current", "added")).thenReturn(List.of(entry));
        when(extractor.readDocuments(eq(entry), eq(WORK_DIR), eq(0), isNull(), eq(false)))
            .thenReturn(Flux.just(document("new", 5)));

        var docs = source(mode).readDocuments(new EsShardPartition("current", "added", 0), 0).collectList().block();
        if (mode == DeltaMode.DELETES_ONLY) {
            assertEquals(List.of(), docs);
            verify(extractor, never()).readDocuments(any(), any(), anyInt(), any(), anyBoolean());
        } else {
            assertEquals(List.of("new"), docs.stream().map(Document::id).toList());
            assertEquals(Document.Operation.UPSERT, docs.get(0).operation());
        }
        verify(extractor, never()).listShards("previous", "added");
    }

    @ParameterizedTest
    @EnumSource(DeltaMode.class)
    void removedIndicesOnlyEmitDeletionsAndPreserveRouting(DeltaMode mode) {
        when(extractor.listIndices("previous")).thenReturn(List.of("removed"));
        when(extractor.listIndices("current")).thenReturn(List.of());
        var entry = shard("previous", "removed", 0);
        when(extractor.listShards("previous", "removed")).thenReturn(List.of(entry));
        when(extractor.readDocuments(eq(entry), eq(WORK_DIR), eq(0), isNull(), eq(false)))
            .thenReturn(Flux.just(document("old", 8)));

        var docs = source(mode).readDocuments(new EsShardPartition("current", "removed", 0), 0).collectList().block();
        if (mode == DeltaMode.UPDATES_ONLY) {
            assertEquals(List.of(), docs);
        } else {
            assertEquals(List.of("old"), docs.stream().map(Document::id).toList());
            assertEquals(Document.Operation.DELETE, docs.get(0).operation());
            assertEquals("route", docs.get(0).hints().get(Document.HINT_ROUTING));
            assertEquals("type", docs.get(0).hints().get(Document.HINT_TYPE));
        }
        verify(extractor, never()).listShards("current", "removed");
    }

    @Test
    void missingBaselineFailsInsteadOfSilentlyDoingAFullCopy() {
        when(extractor.listSnapshots()).thenReturn(List.of("current"));
        assertThrows(IllegalArgumentException.class, () -> source(DeltaMode.UPDATES_ONLY).listCollections());
    }

    @Test
    void unreadableBaselineShardFailsInsteadOfSilentlyDoingAFullCopy() {
        when(extractor.listIndices("previous")).thenReturn(List.of("shared"));
        when(extractor.listIndices("current")).thenReturn(List.of("shared"));
        when(extractor.listShards("current", "shared")).thenReturn(List.of(shard("current", "shared", 0)));
        when(extractor.listShards("previous", "shared")).thenThrow(new IllegalStateException("corrupt baseline"));
        assertThrows(IllegalStateException.class, () -> source(DeltaMode.UPDATES_ONLY).listPartitions("shared"));
    }

    @Test
    void resumeCountsEmittedDocumentsRatherThanLuceneIdsWithDeletedOrNestedGaps() {
        when(extractor.listIndices("previous")).thenReturn(List.of());
        when(extractor.listIndices("current")).thenReturn(List.of("added"));
        var entry = shard("current", "added", 0);
        when(extractor.listShards("current", "added")).thenReturn(List.of(entry));
        when(extractor.readDocuments(eq(entry), eq(WORK_DIR), eq(0), isNull(), eq(false)))
            .thenReturn(Flux.just(document("first", 3), document("second", 8), document("third", 15)));

        var docs = source(DeltaMode.UPDATES_ONLY)
            .readDocuments(new EsShardPartition("current", "added", 0), 2).collectList().block();
        assertEquals(List.of("third"), docs.stream().map(Document::id).toList());
    }

    @Test
    void sharedShardResumesWithinTheSelectedDeltaPhase() {
        when(extractor.listIndices("previous")).thenReturn(List.of("shared"));
        when(extractor.listIndices("current")).thenReturn(List.of("shared"));
        var current = shard("current", "shared", 0);
        var previous = shard("previous", "shared", 0);
        when(extractor.listShards("current", "shared")).thenReturn(List.of(current));
        when(extractor.listShards("previous", "shared")).thenReturn(List.of(previous));
        when(extractor.readDeltaDocuments(eq(current), eq(previous), eq(DeltaMode.UPDATES_ONLY), eq(WORK_DIR), any()))
            .thenReturn(Flux.just(document("first", 3), document("second", 15)));

        var docs = source(DeltaMode.UPDATES_ONLY)
            .readDocuments(new EsShardPartition("current", "shared", 0), 1).collectList().block();
        assertEquals(List.of("second"), docs.stream().map(Document::id).toList());
    }

    private static LuceneDocumentChange document(String id, int luceneId) {
        return new LuceneDocumentChange(luceneId, id, "type", new byte[] {'{', '}'}, "route", DocumentChangeType.INDEX);
    }
}
