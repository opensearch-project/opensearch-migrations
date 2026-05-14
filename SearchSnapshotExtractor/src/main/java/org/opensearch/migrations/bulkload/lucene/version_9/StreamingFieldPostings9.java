package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.ArrayList;
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

        // Collect (term, position, startOff, endOff) for every cursor whose head equals docId.
        // We then sort by position to deliver position-ordered output, mirroring the sidecar contract.
        ArrayList<TermEntry> collected = new ArrayList<>();
        ArrayList<int[]> posOffsets = new ArrayList<>(); // parallel arrays: [pos, startOff, endOff, termIdx-into-collected]
        while (heapSize > 0 && heap[0].currentDoc == docId) {
            Cursor c = heap[0];
            int freq = c.postings.freq();
            for (int i = 0; i < freq; i++) {
                int pos = c.postings.nextPosition();
                if (pos < 0) continue;
                int startOff = fieldHasOffsets ? c.postings.startOffset() : PostingsSink.NO_OFFSET;
                int endOff = fieldHasOffsets ? c.postings.endOffset() : PostingsSink.NO_OFFSET;
                posOffsets.add(new int[]{pos, startOff, endOff, collected.size()});
                // Stash term placeholder; index into a parallel array for cheap re-lookup.
                collected.add(new TermEntry(c.term, startOff, endOff));
                // We'll overwrite collected entries after sort, but we need term per index.
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

        if (posOffsets.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort by position ascending; tiebreak by collected order for determinism.
        posOffsets.sort((a, b) -> {
            int d = Integer.compare(a[0], b[0]);
            return d != 0 ? d : Integer.compare(a[3], b[3]);
        });

        ArrayList<TermEntry> ordered = new ArrayList<>(posOffsets.size());
        for (int[] po : posOffsets) {
            TermEntry placeholder = collected.get(po[3]);
            // collected[i] stores the correct term + offsets already.
            ordered.add(new TermEntry(placeholder.term(), po[1], po[2]));
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
