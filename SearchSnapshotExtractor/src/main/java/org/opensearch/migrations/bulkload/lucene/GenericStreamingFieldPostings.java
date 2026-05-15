package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


import lombok.extern.slf4j.Slf4j;

/**
 * Version-independent streaming postings cursor using a min-heap of per-term cursors.
 * Each version-specific LeafReader creates an instance via {@link #build}, passing
 * an iterator of (term, postingsCursor) pairs extracted from the version-specific
 * Lucene TermsEnum/PostingsEnum.
 */
@Slf4j
public final class GenericStreamingFieldPostings implements StreamingFieldPostings {

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

    /** A (term, cursor) pair provided by the version-specific builder. */
    public record TermPostings(String term, PostingsCursor cursor, int firstDoc) {
        // Record — no additional methods needed.
    }

    private static final class Cursor {
        final String term;
        final PostingsCursor postings;
        int currentDoc;

        Cursor(String term, PostingsCursor postings, int currentDoc) {
            this.term = term;
            this.postings = postings;
            this.currentDoc = currentDoc;
        }
    }

    private final boolean fieldHasOffsets;
    private final Cursor[] heap;
    private int heapSize;
    private int lastAdvancedDoc = -1;
    private boolean closed;

    private long[] scratchSortKey = new long[64];
    private String[] scratchTerm = new String[64];
    private int[] scratchStart;
    private int[] scratchEnd;

    /**
     * Builds a streaming field postings cursor from a list of term-postings pairs.
     *
     * @param termPostings all terms for the field with their positioned postings cursors,
     *                     pre-advanced to their first doc
     * @param fieldHasOffsets whether the field was indexed with character offsets
     */
    public static GenericStreamingFieldPostings build(List<TermPostings> termPostings, boolean fieldHasOffsets) {
        return new GenericStreamingFieldPostings(termPostings, fieldHasOffsets);
    }

    private GenericStreamingFieldPostings(List<TermPostings> termPostings, boolean fieldHasOffsets) {
        this.fieldHasOffsets = fieldHasOffsets;
        if (fieldHasOffsets) {
            this.scratchStart = new int[64];
            this.scratchEnd = new int[64];
        }

        this.heap = new Cursor[termPostings.size()];
        for (int i = 0; i < termPostings.size(); i++) {
            TermPostings tp = termPostings.get(i);
            heap[i] = new Cursor(tp.term(), tp.cursor(), tp.firstDoc());
        }
        this.heapSize = heap.length;
        for (int i = (heapSize >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    @Override
    public List<TermEntry> advance(int docId) throws IOException {
        if (closed) {
            throw new IOException("StreamingFieldPostings is closed");
        }
        if (docId < lastAdvancedDoc) {
            throw new IllegalStateException("StreamingFieldPostings cannot rewind: requested "
                    + docId + " after " + lastAdvancedDoc);
        }
        lastAdvancedDoc = docId;

        if (heapSize == 0) {
            return Collections.emptyList();
        }

        skipToDoc(docId);

        if (heapSize == 0 || heap[0].currentDoc != docId) {
            return Collections.emptyList();
        }

        int n = collectIntoScratch(docId);

        if (n == 0) {
            return Collections.emptyList();
        }

        return sortAndBuildResult(n);
    }

    private void skipToDoc(int docId) throws IOException {
        while (heapSize > 0 && heap[0].currentDoc < docId) {
            Cursor c = heap[0];
            int next = c.postings.advance(docId);
            if (next == PostingsCursor.NO_MORE_DOCS) {
                heap[0] = heap[--heapSize];
                heap[heapSize] = null;
            } else {
                c.currentDoc = next;
            }
            if (heapSize > 0) {
                siftDown(0);
            }
        }
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

    private void advanceHeapHead() throws IOException {
        int next = heap[0].postings.nextDoc();
        if (next == PostingsCursor.NO_MORE_DOCS) {
            heap[0] = heap[--heapSize];
            heap[heapSize] = null;
        } else {
            heap[0].currentDoc = next;
        }
        if (heapSize > 0) {
            siftDown(0);
        }
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

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < heap.length; i++) {
            heap[i] = null;
        }
        heapSize = 0;
    }

    private void siftDown(int i) {
        int n = heapSize;
        while (true) {
            int l = (i << 1) + 1;
            int r = l + 1;
            int smallest = i;
            if (l < n && heap[l].currentDoc < heap[smallest].currentDoc) smallest = l;
            if (r < n && heap[r].currentDoc < heap[smallest].currentDoc) smallest = r;
            if (smallest == i) return;
            Cursor tmp = heap[i];
            heap[i] = heap[smallest];
            heap[smallest] = tmp;
            i = smallest;
        }
    }
}
