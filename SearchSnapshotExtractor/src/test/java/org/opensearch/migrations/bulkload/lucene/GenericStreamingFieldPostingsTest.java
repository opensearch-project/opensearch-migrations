package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.GenericStreamingFieldPostings.PostingsCursor;
import org.opensearch.migrations.bulkload.lucene.GenericStreamingFieldPostings.TermPostings;
import org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink;
import org.opensearch.migrations.bulkload.lucene.sidecar.TermEntry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GenericStreamingFieldPostings}.
 *
 * <p>Uses real in-memory {@link PostingsCursor} implementations — no mocking.
 */
class GenericStreamingFieldPostingsTest {

    // -------------------------------------------------------------------------
    //  In-memory PostingsCursor: each doc has (docId, freq, positions[], startOffsets[], endOffsets[])
    // -------------------------------------------------------------------------

    /** One document's worth of posting data for a single term. */
    private record DocPosting(int docId, int[] positions, int[] startOffsets, int[] endOffsets) {
        static DocPosting of(int docId, int... positions) {
            return new DocPosting(docId, positions, null, null);
        }

        static DocPosting withOffsets(int docId, int[] positions, int[] startOffsets, int[] endOffsets) {
            return new DocPosting(docId, positions, startOffsets, endOffsets);
        }
    }

    /**
     * A simple in-memory PostingsCursor backed by a sorted list of DocPostings.
     * Supports nextDoc(), advance(), freq(), nextPosition(), startOffset(), endOffset().
     */
    private static final class InMemoryCursor implements PostingsCursor {
        private final List<DocPosting> docs;
        private int docIdx = -1;   // index into docs list
        private int posIdx = -1;   // index into current doc's positions

        InMemoryCursor(List<DocPosting> docs) {
            this.docs = docs;
        }

        @Override
        public int nextDoc() {
            docIdx++;
            posIdx = -1;
            if (docIdx >= docs.size()) {
                return NO_MORE_DOCS;
            }
            return docs.get(docIdx).docId();
        }

        @Override
        public int advance(int target) {
            // Linear scan forward to find first doc >= target
            while (true) {
                int doc = nextDoc();
                if (doc == NO_MORE_DOCS || doc >= target) {
                    return doc;
                }
            }
        }

        @Override
        public int freq() {
            return docs.get(docIdx).positions().length;
        }

        @Override
        public int nextPosition() {
            posIdx++;
            return docs.get(docIdx).positions()[posIdx];
        }

        @Override
        public int startOffset() {
            int[] offsets = docs.get(docIdx).startOffsets();
            return (offsets != null) ? offsets[posIdx] : PostingsSink.NO_OFFSET;
        }

        @Override
        public int endOffset() {
            int[] offsets = docs.get(docIdx).endOffsets();
            return (offsets != null) ? offsets[posIdx] : PostingsSink.NO_OFFSET;
        }
    }

    /**
     * Helper: create a TermPostings pre-advanced to firstDoc.
     * The cursor is advanced via nextDoc() once so that firstDoc is set.
     */
    private static TermPostings tp(String term, List<DocPosting> docs) throws IOException {
        InMemoryCursor cursor = new InMemoryCursor(docs);
        int firstDoc = cursor.nextDoc();
        return new TermPostings(term, cursor, firstDoc);
    }

    // -------------------------------------------------------------------------
    //  Scenario 1: single term, single doc
    // -------------------------------------------------------------------------

    @Test
    void singleTermSingleDoc_returnsTermAtPosition() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("hello", List.of(DocPosting.of(0, 0)))),
            false
        );

        List<TermEntry> entries = sfp.advance(0);
        assertEquals(1, entries.size());
        assertEquals("hello", entries.get(0).term());
        assertEquals(0, entries.get(0).position());
        assertEquals(PostingsSink.NO_OFFSET, entries.get(0).startOffset());
        assertEquals(PostingsSink.NO_OFFSET, entries.get(0).endOffset());

        sfp.close();
    }

    // -------------------------------------------------------------------------
    //  Scenario 2: multi-term, single doc — heap merge produces sorted terms by position
    // -------------------------------------------------------------------------

    @Test
    void multiTermSingleDoc_returnsSortedByPosition() throws IOException {
        // Three terms all present in doc 5, at different positions
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(
                tp("cherry", List.of(DocPosting.of(5, 2))),
                tp("apple",  List.of(DocPosting.of(5, 0))),
                tp("banana", List.of(DocPosting.of(5, 1)))
            ),
            false
        );

        List<TermEntry> entries = sfp.advance(5);
        assertEquals(3, entries.size());
        // Should be sorted by position
        assertEquals("apple",  entries.get(0).term());
        assertEquals(0,        entries.get(0).position());
        assertEquals("banana", entries.get(1).term());
        assertEquals(1,        entries.get(1).position());
        assertEquals("cherry", entries.get(2).term());
        assertEquals(2,        entries.get(2).position());

        sfp.close();
    }

    @Test
    void multiTermSamePosition_stableOutput() throws IOException {
        // Two terms at the same position in the same doc
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(
                tp("alpha", List.of(DocPosting.of(0, 1))),
                tp("beta",  List.of(DocPosting.of(0, 1)))
            ),
            false
        );

        List<TermEntry> entries = sfp.advance(0);
        assertEquals(2, entries.size());
        // Both at position 1
        assertEquals(1, entries.get(0).position());
        assertEquals(1, entries.get(1).position());

        sfp.close();
    }

    // -------------------------------------------------------------------------
    //  Scenario 3: multi-doc advance — forward-only semantics
    // -------------------------------------------------------------------------

    @Test
    void multiDocAdvance_forwardOnlySemantics() throws IOException {
        // Term "word" present in docs 1, 3, 5
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("word", List.of(
                DocPosting.of(1, 0),
                DocPosting.of(3, 0),
                DocPosting.of(5, 0)
            ))),
            false
        );

        // Advance to doc 1
        List<TermEntry> e1 = sfp.advance(1);
        assertEquals(1, e1.size());
        assertEquals("word", e1.get(0).term());

        // Skip doc 2 (not present)
        List<TermEntry> e2 = sfp.advance(2);
        assertTrue(e2.isEmpty());

        // Advance to doc 3
        List<TermEntry> e3 = sfp.advance(3);
        assertEquals(1, e3.size());

        // Advance to doc 5
        List<TermEntry> e5 = sfp.advance(5);
        assertEquals(1, e5.size());

        // Advance beyond all docs
        List<TermEntry> e6 = sfp.advance(10);
        assertTrue(e6.isEmpty());

        sfp.close();
    }

    @Test
    void advanceBackward_throwsIllegalState() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("term", List.of(DocPosting.of(5, 0)))),
            false
        );

        sfp.advance(5);
        assertThrows(IllegalStateException.class, () -> sfp.advance(3));

        sfp.close();
    }

    @Test
    void advanceSameDocTwice_returnsEmptySecondTime() throws IOException {
        // Advancing to the same doc twice: first returns results, second returns empty
        // because lastAdvancedDoc == docId is allowed but postings were already drained
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("once", List.of(DocPosting.of(2, 0)))),
            false
        );

        List<TermEntry> first = sfp.advance(2);
        assertEquals(1, first.size());

        // Same docId again is allowed (non-decreasing), but the cursor already moved past it
        List<TermEntry> second = sfp.advance(2);
        assertTrue(second.isEmpty());

        sfp.close();
    }

    // -------------------------------------------------------------------------
    //  Scenario 4: empty term list
    // -------------------------------------------------------------------------

    @Test
    void emptyTermList_buildReturnsObject_advanceReturnsEmpty() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            Collections.emptyList(),
            false
        );

        List<TermEntry> entries = sfp.advance(0);
        assertTrue(entries.isEmpty());

        // Advancing further should also be empty
        entries = sfp.advance(100);
        assertTrue(entries.isEmpty());

        sfp.close();
    }

    // -------------------------------------------------------------------------
    //  Scenario 5: close lifecycle — after close(), advance() throws IOException
    // -------------------------------------------------------------------------

    @Test
    void afterClose_advanceThrowsIOException() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("term", List.of(DocPosting.of(0, 0)))),
            false
        );

        sfp.close();
        assertThrows(IOException.class, () -> sfp.advance(0));
    }

    @Test
    void doubleClose_isIdempotent() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("term", List.of(DocPosting.of(0, 0)))),
            false
        );

        sfp.close();
        sfp.close(); // Should not throw
    }

    // -------------------------------------------------------------------------
    //  Scenario 6: offset collection (fieldHasOffsets=true)
    // -------------------------------------------------------------------------

    @Test
    void withOffsets_populatesStartAndEndOffset() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(
                tp("world", List.of(
                    DocPosting.withOffsets(0, new int[]{1}, new int[]{6}, new int[]{11})
                )),
                tp("hello", List.of(
                    DocPosting.withOffsets(0, new int[]{0}, new int[]{0}, new int[]{5})
                ))
            ),
            true
        );

        List<TermEntry> entries = sfp.advance(0);
        assertEquals(2, entries.size());

        // Sorted by position: hello(pos=0) then world(pos=1)
        assertEquals("hello", entries.get(0).term());
        assertEquals(0,       entries.get(0).position());
        assertEquals(0,       entries.get(0).startOffset());
        assertEquals(5,       entries.get(0).endOffset());

        assertEquals("world", entries.get(1).term());
        assertEquals(1,       entries.get(1).position());
        assertEquals(6,       entries.get(1).startOffset());
        assertEquals(11,      entries.get(1).endOffset());

        sfp.close();
    }

    @Test
    void withOffsets_multiplePositionsPerTerm() throws IOException {
        // A term appears multiple times in one doc
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(
                tp("the", List.of(
                    DocPosting.withOffsets(0,
                        new int[]{0, 3},
                        new int[]{0, 15},
                        new int[]{3, 18})
                ))
            ),
            true
        );

        List<TermEntry> entries = sfp.advance(0);
        assertEquals(2, entries.size());

        assertEquals("the", entries.get(0).term());
        assertEquals(0,     entries.get(0).position());
        assertEquals(0,     entries.get(0).startOffset());
        assertEquals(3,     entries.get(0).endOffset());

        assertEquals("the", entries.get(1).term());
        assertEquals(3,     entries.get(1).position());
        assertEquals(15,    entries.get(1).startOffset());
        assertEquals(18,    entries.get(1).endOffset());

        sfp.close();
    }

    // -------------------------------------------------------------------------
    //  Additional: multi-term, multi-doc interleaved
    // -------------------------------------------------------------------------

    @Test
    void multiTermMultiDoc_interleavedPostings() throws IOException {
        // term "a" in docs 0, 2; term "b" in docs 1, 2
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(
                tp("a", List.of(DocPosting.of(0, 0), DocPosting.of(2, 0))),
                tp("b", List.of(DocPosting.of(1, 0), DocPosting.of(2, 1)))
            ),
            false
        );

        // doc 0: only "a"
        List<TermEntry> e0 = sfp.advance(0);
        assertEquals(1, e0.size());
        assertEquals("a", e0.get(0).term());

        // doc 1: only "b"
        List<TermEntry> e1 = sfp.advance(1);
        assertEquals(1, e1.size());
        assertEquals("b", e1.get(0).term());

        // doc 2: both "a"(pos=0) and "b"(pos=1)
        List<TermEntry> e2 = sfp.advance(2);
        assertEquals(2, e2.size());
        assertEquals("a", e2.get(0).term());
        assertEquals(0,   e2.get(0).position());
        assertEquals("b", e2.get(1).term());
        assertEquals(1,   e2.get(1).position());

        // doc 3: nothing left
        List<TermEntry> e3 = sfp.advance(3);
        assertTrue(e3.isEmpty());

        sfp.close();
    }

    @Test
    void allTermsExhausted_returnsEmptyForSubsequentAdvances() throws IOException {
        GenericStreamingFieldPostings sfp = GenericStreamingFieldPostings.build(
            List.of(tp("x", List.of(DocPosting.of(0, 0)))),
            false
        );

        // Skip past the only doc
        List<TermEntry> entries = sfp.advance(99);
        assertTrue(entries.isEmpty());

        // All further advances should be empty
        entries = sfp.advance(100);
        assertTrue(entries.isEmpty());

        sfp.close();
    }
}
