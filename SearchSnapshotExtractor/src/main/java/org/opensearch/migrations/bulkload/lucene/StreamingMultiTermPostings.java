package org.opensearch.migrations.bulkload.lucene;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Streaming cursor over a (segment, field) that yields the per-doc multiset of
 * indexed terms (each term repeated by its per-doc frequency).
 *
 * <p>Forward-only — like {@link StreamingFieldPostings} but for the FREQS-only
 * path (no positions, no offsets). Used to recover multi-valued keyword /
 * not-analyzed subfields whose source representation is an array of duplicates
 * that {@code SORTED_SET} doc_values cannot reproduce.
 *
 * <p>Memory is bounded by the field's unique-term count, not by the corpus
 * size: one {@code PostingsEnum} + one decoded term {@code String} per term
 * lives in the heap. No segment-wide {@code Map<Integer, List<String>>} is
 * materialized — replaces the previous eager builder that OOMed on
 * high-cardinality text fields.
 *
 * <p>Required call discipline: {@link #advance(int)} must be invoked with
 * strictly non-decreasing {@code docId} values. The cursor cannot rewind.
 * Implementations may throw {@link IllegalStateException} if a regression is
 * detected.
 *
 * <p>Order: the returned list is a <i>multiset</i> — callers must not depend
 * on the inter-term order. Frequencies and the full set of distinct terms are
 * the only guaranteed properties.
 *
 * <p>Lifetime is owned by {@link SegmentTermIndex}; {@link #close()} releases
 * all open postings enumerators and clears the per-field dictionary.
 */
public interface StreamingMultiTermPostings extends Closeable {

    /**
     * Returns the multiset of indexed tokens for {@code docId} — each term
     * repeated by its per-doc frequency. Returns an empty list if the doc has
     * no tokens in this field.
     *
     * <p>The order across distinct terms is unspecified. Callers must not bind
     * to a particular drain order; consume the result as a multiset.
     *
     * @param docId target docId — must be greater than or equal to the docId
     *              passed to the previous {@code advance} call (or any
     *              non-negative value on the first call).
     */
    List<String> advance(int docId) throws IOException;

    @Override
    void close() throws IOException;
}
