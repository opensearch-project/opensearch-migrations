package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.StreamingFieldPostings;
import org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink;
import org.opensearch.migrations.bulkload.lucene.sidecar.TermEntry;

import lombok.extern.slf4j.Slf4j;
import shadow.lucene9.org.apache.lucene.index.FieldInfo;
import shadow.lucene9.org.apache.lucene.index.IndexOptions;
import shadow.lucene9.org.apache.lucene.index.LeafReader;
import shadow.lucene9.org.apache.lucene.index.PostingsEnum;
import shadow.lucene9.org.apache.lucene.index.Terms;
import shadow.lucene9.org.apache.lucene.index.TermsEnum;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

/**
 * Streaming postings cursor for Lucene 9 fields.
 *
 * <p>Opens one PostingsEnum per term up-front and keeps them in a min-heap keyed by
 * current docId. Each {@link #advance(int)} drains all postings whose head docId
 * matches the requested target, advancing each drained cursor to its next doc and
 * re-heapifying.
 *
 * <p>Memory is bounded by the field's term count: one PostingsEnum + one decoded
 * term string per term. Postings are NOT pre-materialized — the implementation
 * exploits Lucene's lazy posting iterators, so per-term cost is constant up-front
 * and the rest is paid only as docs are visited.
 *
 * <p>Position-list buffer per (term, doc) is reused across heap entries; the entry
 * objects themselves are created fresh per advance result so callers can keep them
 * across subsequent advance calls (ArrayList of immutable {@link TermEntry}).
 */
@Slf4j
final class StreamingFieldPostings9 implements StreamingFieldPostings {

    /**
     * One entry per term in the field's posting list. {@code currentDoc} is the doc
     * the underlying PostingsEnum is currently positioned at, or
     * {@link PostingsEnum#NO_MORE_DOCS} once exhausted.
     */
    private static final class Cursor {
        final String term;
        final PostingsEnum postings;
        int currentDoc;

        Cursor(String term, PostingsEnum postings, int currentDoc) {
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

    // Reusable scratch buffers for the per-doc collect-and-sort path. Grow on demand;
    // never shrink. Encoding: sortKey[i] = (pos << 32) | (i & 0xFFFFFFFFL), so a single
    // primitive Arrays.sort orders by position ascending with i as the stable tiebreak.
    private long[] scratchSortKey = new long[64];
    private String[] scratchTerm = new String[64];
    private int[] scratchStart;
    private int[] scratchEnd;

    StreamingFieldPostings9(LeafReader wrapped, String fieldName) throws IOException {
        Terms terms = wrapped.terms(fieldName);
        if (terms == null || !terms.hasPositions()) {
            this.heap = new Cursor[0];
            this.heapSize = 0;
            this.fieldHasOffsets = false;
            return;
        }
        FieldInfo fi = wrapped.getFieldInfos().fieldInfo(fieldName);
        this.fieldHasOffsets = fi != null
            && fi.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) >= 0;
        int postingsFlags = fieldHasOffsets ? PostingsEnum.OFFSETS : PostingsEnum.POSITIONS;
        if (fieldHasOffsets) {
            this.scratchStart = new int[64];
            this.scratchEnd = new int[64];
        }

        // Estimate term count to size the heap. Terms.size() returns -1 if unknown;
        // we fall back to an ArrayList-style growth pattern via a temporary list.
        long estTerms = terms.size();
        ArrayList<Cursor> built = new ArrayList<>(estTerms > 0 && estTerms < Integer.MAX_VALUE
                ? (int) estTerms
                : 64);

        TermsEnum te = terms.iterator();
        BytesRef term;
        while ((term = te.next()) != null) {
            String termStr = term.utf8ToString();
            PostingsEnum postings = te.postings(null, postingsFlags);
            int firstDoc = postings.nextDoc();
            if (firstDoc == PostingsEnum.NO_MORE_DOCS) {
                continue; // nothing to stream for this term
            }
            built.add(new Cursor(termStr, postings, firstDoc));
        }

        this.heap = built.toArray(new Cursor[0]);
        this.heapSize = heap.length;
        // Heapify in O(n).
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

    /** Advances all heap cursors that are behind {@code docId}, dropping exhausted ones. */
    private void skipToDoc(int docId) throws IOException {
        while (heapSize > 0 && heap[0].currentDoc < docId) {
            Cursor c = heap[0];
            int next = c.postings.advance(docId);
            if (next == PostingsEnum.NO_MORE_DOCS) {
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

    /**
     * Drains all heap cursors positioned at {@code docId} into the reusable scratch buffers.
     * Encodes each token as {@code (pos << 32) | idx} for a single primitive sort pass.
     * Returns the number of tokens written.
     */
    private int collectIntoScratch(int docId) throws IOException {
        int n = 0;
        long[] sortKey = scratchSortKey;
        String[] terms = scratchTerm;
        int[] startOff = scratchStart;
        int[] endOff = scratchEnd;
        boolean offsets = fieldHasOffsets;

        while (heapSize > 0 && heap[0].currentDoc == docId) {
            Cursor c = heap[0];
            int freq = c.postings.freq();
            int need = n + freq;
            if (need > sortKey.length) {
                int newLen = Math.max(need, sortKey.length << 1);
                sortKey = Arrays.copyOf(sortKey, newLen);
                terms = Arrays.copyOf(terms, newLen);
                if (offsets) {
                    startOff = Arrays.copyOf(startOff, newLen);
                    endOff = Arrays.copyOf(endOff, newLen);
                }
            }
            n = drainCursorPositions(c, sortKey, terms, startOff, endOff, offsets, n, freq);
            n = advanceHeapHead(n);
        }

        scratchSortKey = sortKey;
        scratchTerm = terms;
        if (offsets) {
            scratchStart = startOff;
            scratchEnd = endOff;
        }
        return n;
    }

    private int drainCursorPositions(Cursor c, long[] sortKey, String[] terms,
            int[] startOff, int[] endOff, boolean offsets, int n, int freq) throws IOException {
        for (int i = 0; i < freq; i++) {
            int pos = c.postings.nextPosition();
            if (pos < 0) continue;
            // Pack pos in high 32 bits; n in low 32 bits as stable tiebreak.
            sortKey[n] = ((long) pos << 32) | (n & 0xFFFFFFFFL);
            terms[n] = c.term;
            if (offsets) {
                startOff[n] = c.postings.startOffset();
                endOff[n] = c.postings.endOffset();
            }
            n++;
        }
        return n;
    }

    /**
     * Moves the current heap head to its next doc (dropping if exhausted) and re-heapifies.
     * Returns {@code n} unchanged — signature matches the call site pattern for readability.
     */
    private int advanceHeapHead(int n) throws IOException {
        int next = heap[0].postings.nextDoc();
        if (next == PostingsEnum.NO_MORE_DOCS) {
            heap[0] = heap[--heapSize];
            heap[heapSize] = null;
        } else {
            heap[0].currentDoc = next;
        }
        if (heapSize > 0) {
            siftDown(0);
        }
        return n;
    }

    /**
     * Sorts the first {@code n} entries in {@code scratchSortKey} using a primitive long sort
     * (JDK 21 vectorized intrinsic) and builds the output list.
     */
    private List<TermEntry> sortAndBuildResult(int n) {
        // Primitive long sort — vectorized on JDK 21, much faster than boxed Comparator sort.
        Arrays.sort(scratchSortKey, 0, n);

        ArrayList<TermEntry> ordered = new ArrayList<>(n);
        if (fieldHasOffsets) {
            for (int k = 0; k < n; k++) {
                int idx = (int) scratchSortKey[k];
                ordered.add(new TermEntry(scratchTerm[idx], scratchStart[idx], scratchEnd[idx]));
            }
        } else {
            for (int k = 0; k < n; k++) {
                int idx = (int) scratchSortKey[k];
                ordered.add(new TermEntry(scratchTerm[idx], PostingsSink.NO_OFFSET, PostingsSink.NO_OFFSET));
            }
        }
        return ordered;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // PostingsEnum has no explicit close in Lucene; releasing references is enough.
        for (int i = 0; i < heap.length; i++) {
            heap[i] = null;
        }
        heapSize = 0;
    }

    /** Min-heap sift-down on heap[0..heapSize). */
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
