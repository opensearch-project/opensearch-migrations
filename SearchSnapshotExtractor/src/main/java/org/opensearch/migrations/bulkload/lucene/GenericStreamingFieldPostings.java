package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Streaming postings cursor for fields indexed with positions (and optionally
 * offsets). Returns position-ordered {@link TermEntry} lists per doc.
 *
 * <p>Built from a min-heap of per-term cursors via the shared
 * {@link AbstractStreamingPostingsHeap} skeleton — see that class for
 * heap-memory lifecycle, call discipline, and {@link #close()} semantics.
 *
 * <p>Per-doc work: drain every head at {@code docId}, capture each token's
 * position into a primitive {@code long[]} (encoded as {@code (pos << 32 | idx)}),
 * sort once with the JDK 21 vectorized {@code Arrays.sort(long[])} intrinsic,
 * then materialize {@link TermEntry}s in position order.
 */
@Slf4j
public final class GenericStreamingFieldPostings
        extends AbstractStreamingPostingsHeap<List<TermEntry>>
        implements StreamingFieldPostings {

    /** Abstraction over version-specific PostingsEnum. */
    public interface PostingsCursor {
        int NO_MORE_DOCS = Integer.MAX_VALUE;
        int nextDoc() throws IOException;
        int advance(int target) throws IOException;
        int freq() throws IOException;
        int nextPosition() throws IOException;
        int startOffset() throws IOException;
        int endOffset() throws IOException;
    }

    /** A (term, cursor, firstDoc) triple from the version-specific builder. */
    public record TermPostings(String term, PostingsCursor cursor, int firstDoc)
            implements AbstractStreamingPostingsHeap.TermEntrySource {}

    private final boolean fieldHasOffsets;

    private long[] scratchSortKey = new long[64];
    private String[] scratchTerm = new String[64];
    private int[] scratchStart;
    private int[] scratchEnd;

    /**
     * Builds a streaming field postings cursor.
     *
     * @param termPostings    pre-advanced cursors, one per term in the dictionary
     * @param fieldHasOffsets whether the field was indexed with character offsets
     */
    public static GenericStreamingFieldPostings build(List<TermPostings> termPostings, boolean fieldHasOffsets) {
        return new GenericStreamingFieldPostings(termPostings, fieldHasOffsets);
    }

    private GenericStreamingFieldPostings(List<TermPostings> termPostings, boolean fieldHasOffsets) {
        super(termPostings);
        this.fieldHasOffsets = fieldHasOffsets;
        if (fieldHasOffsets) {
            this.scratchStart = new int[64];
            this.scratchEnd = new int[64];
        }
    }

    @Override
    public List<TermEntry> advance(int docId) throws IOException {
        return advanceCommon(docId, Collections.emptyList());
    }

    @Override
    protected List<TermEntry> drainAtDoc(int docId) throws IOException {
        int n = collectIntoScratch(docId);
        if (n == 0) {
            return Collections.emptyList();
        }
        return sortAndBuildResult(n);
    }

    private int collectIntoScratch(int docId) throws IOException {
        int n = 0;
        long[] sortKey = scratchSortKey;
        String[] terms = scratchTerm;
        int[] startOff = scratchStart;
        int[] endOff = scratchEnd;

        while (heapSize > 0 && heap[0].currentDoc == docId) {
            Cursor c = heap[0];
            int freq = c.postings.freq();
            int need = n + freq;
            if (need > sortKey.length) {
                int newLen = Math.max(need, sortKey.length << 1);
                sortKey = Arrays.copyOf(sortKey, newLen);
                terms = Arrays.copyOf(terms, newLen);
                if (fieldHasOffsets) {
                    startOff = Arrays.copyOf(startOff, newLen);
                    endOff = Arrays.copyOf(endOff, newLen);
                }
            }
            n = drainPositions(c, sortKey, terms, startOff, endOff, n, freq);
            advanceHeapHead();
        }

        scratchSortKey = sortKey;
        scratchTerm = terms;
        if (fieldHasOffsets) {
            scratchStart = startOff;
            scratchEnd = endOff;
        }
        return n;
    }

    private int drainPositions(Cursor c, long[] sortKey, String[] terms,
            int[] startOff, int[] endOff, int n, int freq) throws IOException {
        for (int i = 0; i < freq; i++) {
            int pos = c.postings.nextPosition();
            if (pos < 0) continue;
            sortKey[n] = ((long) pos << 32) | (n & 0xFFFFFFFFL);
            terms[n] = c.term;
            if (fieldHasOffsets) {
                startOff[n] = c.postings.startOffset();
                endOff[n] = c.postings.endOffset();
            }
            n++;
        }
        return n;
    }

    private List<TermEntry> sortAndBuildResult(int n) {
        Arrays.sort(scratchSortKey, 0, n);

        ArrayList<TermEntry> ordered = new ArrayList<>(n);
        if (fieldHasOffsets) {
            for (int k = 0; k < n; k++) {
                long key = scratchSortKey[k];
                int idx = (int) key;
                int pos = (int) (key >>> 32);
                ordered.add(new TermEntry(scratchTerm[idx], pos, scratchStart[idx], scratchEnd[idx]));
            }
        } else {
            for (int k = 0; k < n; k++) {
                long key = scratchSortKey[k];
                int idx = (int) key;
                int pos = (int) (key >>> 32);
                ordered.add(new TermEntry(scratchTerm[idx], pos, TermEntry.NO_OFFSET, TermEntry.NO_OFFSET));
            }
        }
        return ordered;
    }
}
