package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.models.ShardMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LuceneSnapshotSourceCheckpointTest {
    @Test
    void deltaResumeUsesStreamOrderAcrossDeletionsAndAdditions() {
        var extractor = mock(SnapshotExtractor.class);
        var current = new SnapshotExtractor.ShardEntry("current", "idx", "id", 0, mock(ShardMetadata.class));
        var previous = new SnapshotExtractor.ShardEntry("previous", "idx", "id", 0, mock(ShardMetadata.class));
        when(extractor.listShards("current", "idx")).thenReturn(List.of(current));
        when(extractor.listShards("previous", "idx")).thenReturn(List.of(previous));
        // Two snapshot coordinate systems: additions can have smaller doc numbers than deletions.
        when(extractor.readDeltaDocuments(eq(current), eq(previous), any(), any(), any())).thenReturn(Flux.just(
            new LuceneDocumentChange(80, "deleted", null, null, null, DocumentChangeType.DELETE),
            new LuceneDocumentChange(2, "added", null, new byte[] {1}, null, DocumentChangeType.INDEX),
            new LuceneDocumentChange(7, "updated", null, new byte[] {1}, null, DocumentChangeType.INDEX)));
        try (var source = LuceneSnapshotSource.builder(extractor, "current", Path.of("/unused"))
            .delta("previous", DeltaMode.UPDATES_AND_DELETES, () -> null).build()) {
            var partition = source.listPartitions("idx").get(0);
            var documents = source.readDocuments(partition, 0).collectList().block();
            assertEquals(List.of(0L, 1L, 2L),
                documents.stream().map(d -> d.position().orElseThrow()).toList());
            assertEquals(80, documents.get(0).sourceMetadata().get(Document.SOURCE_META_LUCENE_DOC_NUMBER));
            for (int i = 0; i < documents.size(); i++) {
                assertEquals(documents.subList(i, documents.size()),
                    source.readDocuments(partition, documents.get(i).position().orElseThrow()).collectList().block());
            }
            assertTrue(source.countLiveDocuments(partition).isEmpty(),
                "delta operations do not provide a whole-shard live count");
        }
    }
}
