package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Streaming postings cursor for the FREQS-only path: yields the per-doc multiset
 * of indexed terms (each term repeated by its per-doc frequency). Used to recover
 * multi-valued keyword / not-analyzed subfields whose source representation is an
 * array of duplicates that {@code SORTED_SET} doc_values cannot reproduce.
 *
 * <p>Built from a min-heap of per-term cursors via the shared
 * {@link AbstractStreamingPostingsHeap} skeleton — see that class for
 * heap-memory lifecycle, call discipline, and {@link #close()} semantics.
 *
 * <p>Per-doc work: drain every head at {@code docId}, repeat each cursor's
 * cached {@code String} reference {@code freq} times into the output list. No
 * sort, no scratch buffers — this path returns the per-doc <i>multiset</i> of
 * terms; the order across distinct terms is heap-internal and unspecified.
 * Callers must consume the result as a multiset (the production contract on
 * {@link SegmentTermIndex#getMultiTermsForDocument} explicitly does not bind
 * the order).
 */
@Slf4j
public final class GenericStreamingMultiTermPostings
        extends AbstractStreamingPostingsHeap<List<String>>
        implements StreamingMultiTermPostings {

    /** A {@code (term, cursor, firstDoc)} triple provided by the version-specific builder. */
    public record TermPostings(String term, GenericStreamingFieldPostings.PostingsCursor cursor, int firstDoc)
            implements AbstractStreamingPostingsHeap.TermEntrySource {}

    /** Builds a streaming multi-term cursor from pre-advanced term-postings pairs. */
    public static GenericStreamingMultiTermPostings build(List<TermPostings> termPostings) {
        return new GenericStreamingMultiTermPostings(termPostings);
    }

    private GenericStreamingMultiTermPostings(List<TermPostings> termPostings) {
        super(termPostings);
    }

    @Override
    public List<String> advance(int docId) throws IOException {
        return advanceCommon(docId, Collections.emptyList());
    }

    @Override
    protected List<String> drainAtDoc(int docId) throws IOException {
        ArrayList<String> out = new ArrayList<>();
        while (heapSize > 0 && heap[0].currentDoc == docId) {
            Cursor c = heap[0];
            int freq = c.postings.freq();
            String termStr = c.term;
            for (int i = 0; i < freq; i++) {
                out.add(termStr);
            }
            advanceHeapHead();
        }
        return out;
    }
}
