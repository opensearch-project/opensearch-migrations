package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.GenericStreamingFieldPostings.PostingsCursor;
import org.opensearch.migrations.bulkload.lucene.GenericStreamingMultiTermPostings.TermPostings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GenericStreamingMultiTermPostings}.
 *
 * <p>Covers the FREQS-only streaming path used by {@code SegmentTermIndex.getMultiTermsForDocument}
 * to recover multi-valued keyword / not-analyzed subfields. Memory profile of the cursor is the
 * key claim here: dictionary lives on per-term {@code Cursor} slots and per-doc emission reuses
 * the cached {@code String} reference across the {@code freq} repetitions, so we also assert
 * reference-equality in the duplicate-emission scenario.
 *
 * <p>Multi-term ordering note: the heap is keyed on {@code currentDoc}, so when multiple terms
 * share the same doc the drained order is heap-internal and not guaranteed dictionary-order.
 * The production contract on {@code getMultiTermsForDocument} explicitly does not bind the
 * order — callers consume the result as a multiset — so the tests below assert content +
 * frequency without binding to order.
 *
 * <p>Uses real in-memory {@link PostingsCursor} implementations — no mocking. The fixture
 * intentionally exercises only the FREQS-required surface (nextDoc, advance, freq) so we don't
 * accidentally rely on positions/offsets, which the streaming multi-term path never touches.
 */
class GenericStreamingMultiTermPostingsTest {

    /** One document's worth of posting data for a single term: docId + freq (term-frequency). */
    private record DocPosting(int docId, int freq) {
        static DocPosting of(int docId, int freq) {
            return new DocPosting(docId, freq);
        }
    }

    /**
     * In-memory FREQS-only PostingsCursor. Position/offset methods are unsupported on this
     * cursor — the multi-term path must not call them.
     */
    private static final class InMemoryFreqCursor implements PostingsCursor {
        private final List<DocPosting> docs;
        private int docIdx = -1;

        InMemoryFreqCursor(List<DocPosting> docs) {
            this.docs = docs;
        }

        @Override
        public int nextDoc() {
            docIdx++;
            return docIdx >= docs.size() ? NO_MORE_DOCS : docs.get(docIdx).docId();
        }

        @Override
        public int advance(int target) {
            while (true) {
                int doc = nextDoc();
                if (doc == NO_MORE_DOCS || doc >= target) return doc;
            }
        }

        @Override
        public int freq() {
            return docs.get(docIdx).freq();
        }

        @Override
        public int nextPosition() {
            throw new UnsupportedOperationException("multi-term path must not call nextPosition()");
        }

        @Override
        public int startOffset() {
            throw new UnsupportedOperationException("multi-term path must not call startOffset()");
        }

        @Override
        public int endOffset() {
            throw new UnsupportedOperationException("multi-term path must not call endOffset()");
        }
    }

    /** Builds a TermPostings pre-advanced to its first doc (matching the contract of build()). */
    private static TermPostings tp(String term, List<DocPosting> docs) {
        InMemoryFreqCursor cursor = new InMemoryFreqCursor(docs);
        int firstDoc = cursor.nextDoc();
        return new TermPostings(term, cursor, firstDoc);
    }

    // -------------------------------------------------------------------------
    //  Single term, single doc
    // -------------------------------------------------------------------------

    @Test
    void singleTermSingleDoc_returnsOneTerm() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("hello", List.of(DocPosting.of(0, 1))))
        );

        List<String> terms = cursor.advance(0);
        assertEquals(List.of("hello"), terms);

        cursor.close();
    }

    @Test
    void singleTermFreqGreaterThanOne_repeatsByFrequency() throws IOException {
        // freq=4 → "spam" returned 4 times for doc 0
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("spam", List.of(DocPosting.of(0, 4))))
        );

        List<String> terms = cursor.advance(0);
        assertEquals(List.of("spam", "spam", "spam", "spam"), terms);

        // Reference-equality check: the duplicates must reuse the cached String
        // reference on the cursor, not allocate fresh String objects per repetition.
        assertSame(terms.get(0), terms.get(1));
        assertSame(terms.get(0), terms.get(2));
        assertSame(terms.get(0), terms.get(3));

        cursor.close();
    }

    // -------------------------------------------------------------------------
    //  Multi-term, single doc — dictionary order
    // -------------------------------------------------------------------------

    @Test
    void multiTermSingleDoc_returnsAllAsMultiset() throws IOException {
        // The heap is keyed on currentDoc; ties don't preserve insertion order. Compare as
        // a sorted multiset so the test pins the recovery contract (full term set, no drops)
        // without overspecifying the heap's internal sift-down behavior.
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(
                tp("apple",  List.of(DocPosting.of(5, 1))),
                tp("banana", List.of(DocPosting.of(5, 1))),
                tp("cherry", List.of(DocPosting.of(5, 1)))
            )
        );

        List<String> terms = new ArrayList<>(cursor.advance(5));
        Collections.sort(terms);
        assertEquals(List.of("apple", "banana", "cherry"), terms);

        cursor.close();
    }

    @Test
    void multiTermSingleDoc_freqAware_repeatsEachTermByItsFreq() throws IOException {
        // "alpha" appears once, "beta" appears 3x, "gamma" appears 2x in the same doc.
        // Order across distinct terms is heap-dependent; assert as a sorted multiset so the
        // test verifies the freq-repetition contract without binding to drain order.
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(
                tp("alpha", List.of(DocPosting.of(0, 1))),
                tp("beta",  List.of(DocPosting.of(0, 3))),
                tp("gamma", List.of(DocPosting.of(0, 2)))
            )
        );

        List<String> terms = new ArrayList<>(cursor.advance(0));
        Collections.sort(terms);
        assertEquals(List.of("alpha", "beta", "beta", "beta", "gamma", "gamma"), terms);

        cursor.close();
    }

    // -------------------------------------------------------------------------
    //  Multi-doc forward traversal
    // -------------------------------------------------------------------------

    @Test
    void multiDocAdvance_emitsEachDocsTerms() throws IOException {
        // Three docs, two terms; interleaved coverage:
        //   doc 0: "a"
        //   doc 1: "b"
        //   doc 2: "a","b"   (order across the two heads is not guaranteed)
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(
                tp("a", List.of(DocPosting.of(0, 1), DocPosting.of(2, 1))),
                tp("b", List.of(DocPosting.of(1, 1), DocPosting.of(2, 1)))
            )
        );

        assertEquals(List.of("a"), cursor.advance(0));
        assertEquals(List.of("b"), cursor.advance(1));
        List<String> doc2 = new ArrayList<>(cursor.advance(2));
        Collections.sort(doc2);
        assertEquals(List.of("a", "b"), doc2);
        assertTrue(cursor.advance(3).isEmpty());

        cursor.close();
    }

    @Test
    void skipsDocsNotInPostings() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("word", List.of(
                DocPosting.of(1, 1),
                DocPosting.of(3, 1),
                DocPosting.of(5, 1)
            )))
        );

        assertEquals(List.of("word"), cursor.advance(1));
        assertTrue(cursor.advance(2).isEmpty());
        assertEquals(List.of("word"), cursor.advance(3));
        assertEquals(List.of("word"), cursor.advance(5));
        assertTrue(cursor.advance(10).isEmpty());

        cursor.close();
    }

    @Test
    void crossDocStringReuse_sameTermSameReferenceAcrossDocs() throws IOException {
        // The whole point of the dictionary cache: emitting "x" for doc 0 and doc 1
        // must reuse the same String reference, not decode utf8 twice.
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("x", List.of(DocPosting.of(0, 1), DocPosting.of(1, 1))))
        );

        String first = cursor.advance(0).get(0);
        String second = cursor.advance(1).get(0);
        assertSame(first, second);

        cursor.close();
    }

    @Test
    void distinctTerms_areDistinctReferences() throws IOException {
        // Sanity: two different terms in the same doc must NOT share a String reference.
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(
                tp("foo", List.of(DocPosting.of(0, 1))),
                tp("bar", List.of(DocPosting.of(0, 1)))
            )
        );

        List<String> terms = cursor.advance(0);
        assertEquals(2, terms.size());
        assertNotSame(terms.get(0), terms.get(1));

        cursor.close();
    }

    // -------------------------------------------------------------------------
    //  Lifecycle: forward-only, close idempotence
    // -------------------------------------------------------------------------

    @Test
    void advanceBackward_throwsIllegalState() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("term", List.of(DocPosting.of(5, 1))))
        );

        cursor.advance(5);
        assertThrows(IllegalStateException.class, () -> cursor.advance(3));

        cursor.close();
    }

    @Test
    void advanceSameDocTwice_secondCallReturnsEmpty() throws IOException {
        // Same docId is allowed (non-decreasing), but the head moved past the doc on
        // the first call so the second call drains nothing.
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("once", List.of(DocPosting.of(2, 1))))
        );

        assertEquals(List.of("once"), cursor.advance(2));
        assertTrue(cursor.advance(2).isEmpty());

        cursor.close();
    }

    @Test
    void emptyTermList_alwaysReturnsEmpty() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            Collections.emptyList()
        );

        assertTrue(cursor.advance(0).isEmpty());
        assertTrue(cursor.advance(100).isEmpty());

        cursor.close();
    }

    @Test
    void afterClose_advanceThrowsIOException() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("term", List.of(DocPosting.of(0, 1))))
        );

        cursor.close();
        assertThrows(IOException.class, () -> cursor.advance(0));
    }

    @Test
    void doubleClose_isIdempotent() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("term", List.of(DocPosting.of(0, 1))))
        );

        cursor.close();
        cursor.close(); // must not throw
    }

    @Test
    void exhaustedCursor_returnsEmptyForFurtherAdvances() throws IOException {
        GenericStreamingMultiTermPostings cursor = GenericStreamingMultiTermPostings.build(
            List.of(tp("only", List.of(DocPosting.of(0, 1))))
        );

        // Skip past the only doc, exhausting the heap
        assertTrue(cursor.advance(99).isEmpty());

        // All further advances stay empty (heapSize == 0 fast path)
        assertTrue(cursor.advance(100).isEmpty());
        assertTrue(cursor.advance(Integer.MAX_VALUE - 1).isEmpty());

        cursor.close();
    }
}
