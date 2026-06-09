package org.opensearch.migrations.bulkload.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Shared min-heap skeleton for streaming postings cursors, version-independent.
 *
 * <p>Owns the heap of per-term {@link Cursor}s, the {@link #skipToDoc(int)} loop,
 * the lifecycle state ({@code closed}, {@code lastAdvancedDoc}), and the
 * {@link #close()} cleanup. Subclasses provide only the per-doc emission strategy
 * via {@link #drainAtDoc(int)} — what type to return and how to arrange the
 * drained terms.
 *
 * <h3>Heap-memory profile</h3>
 *
 * <p>Per-segment, per-field cost is {@code O(uniqueTerms)} — one {@link Cursor}
 * per term, each holding a single decoded {@code String} reference and its
 * version-specific {@link GenericStreamingFieldPostings.PostingsCursor}. The
 * dictionary is paid once at build time and reused across all docs in the
 * segment via reference equality. {@link #close()} nulls every slot, so the
 * dictionary plus all postings state become GC-eligible immediately even if a
 * caller leaks the closed instance.
 *
 * <h3>Call discipline</h3>
 *
 * <p>{@code advance(int)} (provided by the {@link StreamingFieldPostings} or
 * {@link StreamingMultiTermPostings} interface that the subclass implements)
 * must be called with strictly non-decreasing {@code docId}s. Regressions throw
 * {@link IllegalStateException}; calls after {@link #close()} throw
 * {@link IOException}.
 *
 * @param <R> result type of {@link #drainAtDoc(int)}
 */
abstract class AbstractStreamingPostingsHeap<R> implements Closeable {

    /** A single per-term entry in the streaming heap. */
    static final class Cursor {
        final String term;
        final GenericStreamingFieldPostings.PostingsCursor postings;
        int currentDoc;

        Cursor(String term, GenericStreamingFieldPostings.PostingsCursor postings, int currentDoc) {
            this.term = term;
            this.postings = postings;
            this.currentDoc = currentDoc;
        }
    }

    /**
     * Adapter shape for builder inputs — both
     * {@link GenericStreamingFieldPostings.TermPostings} and
     * {@link GenericStreamingMultiTermPostings.TermPostings} implement this so
     * the abstract constructor can build the heap without copying records.
     */
    interface TermEntrySource {
        String term();
        GenericStreamingFieldPostings.PostingsCursor cursor();
        int firstDoc();
    }

    final Cursor[] heap;
    int heapSize;
    private int lastAdvancedDoc = -1;
    private boolean closed;

    /**
     * Builds the min-heap from a list of {@code (term, cursor, firstDoc)} triples.
     * Each cursor must already be advanced to {@code firstDoc} (or be excluded if
     * it has no docs).
     */
    AbstractStreamingPostingsHeap(List<? extends TermEntrySource> termPostings) {
        this.heap = new Cursor[termPostings.size()];
        for (int i = 0; i < termPostings.size(); i++) {
            TermEntrySource tp = termPostings.get(i);
            heap[i] = new Cursor(tp.term(), tp.cursor(), tp.firstDoc());
        }
        this.heapSize = heap.length;
        for (int i = (heapSize >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    /**
     * Common entry point invoked by subclasses' typed {@code advance} methods.
     * Validates lifecycle, walks cursors forward to {@code docId}, and delegates
     * to {@link #drainAtDoc(int)} when there's at least one head positioned there.
     */
    final R advanceCommon(int docId, R emptyResult) throws IOException {
        if (closed) {
            throw new IOException(getClass().getSimpleName() + " is closed");
        }
        if (docId < lastAdvancedDoc) {
            throw new IllegalStateException(getClass().getSimpleName()
                    + " cannot rewind: requested " + docId + " after " + lastAdvancedDoc);
        }
        lastAdvancedDoc = docId;

        if (heapSize == 0) {
            return emptyResult;
        }
        skipToDoc(docId);
        if (heapSize == 0 || heap[0].currentDoc != docId) {
            return emptyResult;
        }
        return drainAtDoc(docId);
    }

    /**
     * Subclass hook: drain every heap head currently positioned at {@code docId}
     * and produce the per-doc result. Implementations must call
     * {@link #advanceHeapHead()} after consuming each head so the heap stays in
     * sync.
     */
    protected abstract R drainAtDoc(int docId) throws IOException;

    /** Advances all heap cursors that are behind {@code docId}, dropping exhausted ones. */
    private void skipToDoc(int docId) throws IOException {
        while (heapSize > 0 && heap[0].currentDoc < docId) {
            Cursor c = heap[0];
            int next = c.postings.advance(docId);
            if (next == GenericStreamingFieldPostings.PostingsCursor.NO_MORE_DOCS) {
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
     * Moves the current heap head to its next doc (dropping if exhausted) and
     * re-heapifies. Called by subclasses after consuming the head's data.
     */
    protected final void advanceHeapHead() throws IOException {
        int next = heap[0].postings.nextDoc();
        if (next == GenericStreamingFieldPostings.PostingsCursor.NO_MORE_DOCS) {
            heap[0] = heap[--heapSize];
            heap[heapSize] = null;
        } else {
            heap[0].currentDoc = next;
        }
        if (heapSize > 0) {
            siftDown(0);
        }
    }

    @Override
    public final void close() {
        if (closed) return;
        closed = true;
        // Null every cursor slot so per-term String + PostingsEnum state become
        // GC-eligible immediately, regardless of whether the caller keeps a
        // reference to the closed instance.
        for (int i = 0; i < heap.length; i++) {
            heap[i] = null;
        }
        heapSize = 0;
        onClose();
    }

    /** Subclass hook for any extra cleanup beyond nulling the heap slots. */
    protected void onClose() {
        // default: no-op
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
