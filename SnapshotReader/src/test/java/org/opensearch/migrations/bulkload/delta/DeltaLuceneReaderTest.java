package org.opensearch.migrations.bulkload.delta;

import java.io.IOException;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneDocument;
import org.opensearch.migrations.bulkload.lucene.LuceneField;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeltaLuceneReaderTest {
    private final IRfsContexts.IDeltaStreamContext context = mock(IRfsContexts.IDeltaStreamContext.class);

    @Test
    void identicalCommitsEmitNoChanges() throws IOException {
        var leaf = leaf("_0", "immutable-id", "one", "two");
        var result = delta(leaf, leaf);
        StepVerifier.create(Flux.concat(result.deletions, result.additions)).verifyComplete();
    }

    @Test
    void sharedSegmentComparesLiveDocumentsInBothDirections() throws IOException {
        var previous = leaf("_0", "same-id", "one", "two", "three");
        var current = leaf("_0", "same-id", "one", "two", "three");
        when(previous.getLiveDocs()).thenReturn(liveDocs(0, 1));
        when(current.getLiveDocs()).thenReturn(liveDocs(1, 2));
        var result = delta(previous, current);

        StepVerifier.create(Flux.concat(result.deletions, result.additions))
            .expectNextMatches(doc -> doc.id.equals("one") && doc.operation == DocumentChangeType.DELETE)
            .expectNextMatches(doc -> doc.id.equals("three") && doc.operation == DocumentChangeType.INDEX)
            .expectComplete()
            .verify(Duration.ofSeconds(10));
    }

    @Test
    void nullLiveDocsMeansAllDocumentsAreLive() throws IOException {
        var previous = leaf("_0", "same-id", "one", "two");
        var current = leaf("_0", "same-id", "one", "two");
        when(current.getLiveDocs()).thenReturn(liveDocs(1));
        var forward = delta(previous, current);
        assertEquals(List.of("one"), forward.deletions.map(doc -> doc.id).collectList().block());
        StepVerifier.create(forward.additions).verifyComplete();

        var reverse = delta(current, previous);
        StepVerifier.create(reverse.deletions).verifyComplete();
        assertEquals(List.of("one"), reverse.additions.map(doc -> doc.id).collectList().block());
    }

    @Test
    void reusedSegmentNamesWithDifferentIdsAreRemovedAndAdded() throws IOException {
        assertReplacement(leaf("_0", "old-id", "old"), leaf("_0", "new-id", "new"));
    }

    @Test
    void segmentsWithoutImmutableIdsAreConservativelyRemovedAndAdded() throws IOException {
        assertReplacement(leaf("_0", null, "old"), leaf("_0", null, "new"));
    }

    @Test
    void mergeRewritesUnchangedDocumentAfterDeletingItsOldSegment() throws IOException {
        assertReplacement(leaf("_0", "old-id", "same-document"), leaf("_1", "new-id", "same-document"));
    }

    private void assertReplacement(LuceneLeafReader previous, LuceneLeafReader current) {
        var result = delta(previous, current);
        StepVerifier.create(Flux.concat(result.deletions, result.additions))
            .expectNextMatches(doc -> doc.operation == DocumentChangeType.DELETE)
            .expectNextMatches(doc -> doc.operation == DocumentChangeType.INDEX)
            .expectComplete()
            .verify(Duration.ofSeconds(10));
    }

    private DeltaLuceneReader.DeltaResult delta(LuceneLeafReader previous, LuceneLeafReader current) {
        return DeltaLuceneReader.readDeltaDocsByLeavesFromStartingPosition(
            directory(previous), directory(current), 0, context);
    }

    private static LuceneDirectoryReader directory(LuceneLeafReader leaf) {
        var directory = mock(LuceneDirectoryReader.class);
        var leafContext = mock(LuceneLeafReaderContext.class);
        when(leafContext.reader()).thenReturn(leaf);
        doReturn(List.of(leafContext)).when(directory).leaves();
        return directory;
    }

    private static LuceneLeafReader leaf(String name, String segmentId, String... ids) throws IOException {
        var leaf = mock(LuceneLeafReader.class);
        when(leaf.getSegmentName()).thenReturn(name);
        when(leaf.getSegmentId()).thenReturn(segmentId);
        when(leaf.maxDoc()).thenReturn(ids.length);
        for (int i = 0; i < ids.length; i++) {
            var id = mock(LuceneField.class);
            when(id.name()).thenReturn("_id");
            when(id.asUid()).thenReturn(ids[i]);
            var source = mock(LuceneField.class);
            when(source.name()).thenReturn("_source");
            when(source.utf8Value()).thenReturn(new byte[] {'{', '}'});
            var document = mock(LuceneDocument.class);
            doReturn(List.of(id, source)).when(document).getFields();
            when(leaf.document(i)).thenReturn(document);
        }
        return leaf;
    }

    private static BitSetConverter.FixedLengthBitSet liveDocs(int... ids) {
        var bits = new BitSet();
        for (int id : ids) bits.set(id);
        return new BitSetConverter.FixedLengthBitSet(bits);
    }
}
