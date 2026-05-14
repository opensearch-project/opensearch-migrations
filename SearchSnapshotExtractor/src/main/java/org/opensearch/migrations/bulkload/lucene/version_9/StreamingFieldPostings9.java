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

        // Skip cursors whose currentDoc < docId. Each such cursor must be advanced
        // forward past or to the target, then re-heapified.
        while (heapSize > 0 && heap[0].currentDoc < docId) {
            Cursor c = heap[0];
            int next = c.postings.advance(docId);
            if (next == PostingsEnum.NO_MORE_DOCS) {
                // Drop this cursor: replace heap[0] with last and shrink.
                heap[0] = heap[--heapSize];
                heap[heapSize] = null;
            } else {
                c.currentDoc = next;
            }
            if (heapSize > 0) {
                siftDown(0);
            }
        }

        if (heapSize == 0 || heap[0].currentDoc != docId) {
            return Collections.emptyList();
        }

        // Collect (term, position[, startOff, endOff]) for every cursor whose head equals docId
        // into reusable primitive scratch buffers. Encode (pos, idx) as a packed long so we can
        // call Arrays.sort(long[]) — a vectorized intrinsic on JDK 21 — instead of TimSort over
        // boxed int[] objects with a Comparator. Strings live in a parallel String[] referenced
        // by index. No per-token allocation.
        int n = 0;
        long[] sortKey = scratchSortKey;
        String[] terms = scratchTerm;
        int[] startOff = scratchStart;
        int[] endOff = scratchEnd;
        boolean offsets = fieldHasOffsets;
        while (heapSize > 0 && heap[0].currentDoc == docId) {
            Cursor c = heap[0];
            int freq = c.postings.freq();
            // Grow scratch in one shot if needed.
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
            for (int i = 0; i < freq; i++) {
                int pos = c.postings.nextPosition();
                if (pos < 0) continue;
                // Pack pos in the high 32 bits; n in the low 32 bits as the stable tiebreak.
                // Reinterpreting pos as unsigned via & 0xFFFFFFFFL keeps the comparison correct
                // for any non-negative int (negatives were filtered above).
                sortKey[n] = ((long) pos << 32) | (n & 0xFFFFFFFFL);
                terms[n] = c.term;
                if (offsets) {
                    startOff[n] = c.postings.startOffset();
                    endOff[n] = c.postings.endOffset();
                }
                n++;
            }
            // Advance this cursor to next doc.
            int next = c.postings.nextDoc();
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
        // Persist any growth for next call.
        scratchSortKey = sortKey;
        scratchTerm = terms;
        if (offsets) {
            scratchStart = startOff;
            scratchEnd = endOff;
        }

        if (n == 0) {
            return Collections.emptyList();
        }

        // Primitive long sort — JDK 21 intrinsic, dramatically faster than ArrayList.sort with
        // a boxed Comparator. Pos in high bits orders by position ascending; tiebreak by
        // insertion index (low bits) gives a stable order matching the original logic.
        Arrays.sort(sortKey, 0, n);

        ArrayList<TermEntry> ordered = new ArrayList<>(n);
        if (offsets) {
            for (int k = 0; k < n; k++) {
                int idx = (int) sortKey[k];
                ordered.add(new TermEntry(terms[idx], startOff[idx], endOff[idx]));
            }
        } else {
            for (int k = 0; k < n; k++) {
                int idx = (int) sortKey[k];
                ordered.add(new TermEntry(terms[idx], PostingsSink.NO_OFFSET, PostingsSink.NO_OFFSET));
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
