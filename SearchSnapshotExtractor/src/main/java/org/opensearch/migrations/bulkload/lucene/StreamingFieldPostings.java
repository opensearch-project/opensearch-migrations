package org.opensearch.migrations.bulkload.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.sidecar.TermEntry;

/**
 * Streaming cursor over all postings for a single (segment, field).
 *
 * <p>Forward-only iterator that yields the position-ordered {@link TermEntry} list for a
 * requested {@code docId}, walking the underlying Lucene terms dictionary in a single
 * pass without spilling to disk and without an external sort.
 *
 * <p>Implementation strategy: open one {@code PostingsEnum} per term up-front and keep
 * them in a min-heap keyed by current docId. {@link #advance(int)} drains entries whose
 * head docId equals the target, advancing each drained cursor to its next doc and
 * re-heapifying. The work for a given doc is bounded by the doc's term frequency, not
 * by the field's posting count — so 500k-doc segments stream in O(N · k) where k is
 * average tokens-per-doc, instead of O(N · T · log T) for a full external sort.
 *
 * <p>Required call discipline: {@link #advance(int)} must be invoked with strictly
 * non-decreasing {@code docId} values. The cursor cannot rewind. Implementations may
 * throw {@link IllegalStateException} if a regression is detected.
 *
 * <p>Lifetime is owned by {@link SegmentTermIndex}; {@link #close()} releases all open
 * postings enumerators.
 */
public interface StreamingFieldPostings extends Closeable {

    /**
     * Returns the position-ordered token list for {@code docId}, or
     * {@link java.util.Collections#emptyList()} if the doc has no tokens in this field.
     *
     * @param docId target docId — must be greater than or equal to the docId passed to
     *              the previous {@code advance} call (or any non-negative value on the
     *              first call).
     */
    List<TermEntry> advance(int docId) throws IOException;

    @Override
    void close() throws IOException;
}
