package org.opensearch.migrations.bulkload.lucene;

import java.util.List;

/**
 * Strongly-typed result of {@link LuceneLeafReader#getValueFromPointsOrTerms}: the raw value
 * recovered from a Lucene segment's points/inverted-index tier, before any mapping-aware
 * shape conversion has been applied. Each variant captures one of the three recovery channels.
 *
 * <p>The sealed shape replaces an earlier {@code Optional<Object>} contract whose
 * {@code instanceof List<?>} branch silently accepted any list — including
 * {@code List<String>} produced by SORTED_SET doc_values bleeding in from the copy_to probe
 * path — and crashed with {@code ClassCastException: String cannot be cast to [B} the moment
 * point bytes were dereferenced.
 */
public sealed interface RecoveredValue
        permits RecoveredValue.PointBytes,
                RecoveredValue.NumericTerm,
                RecoveredValue.TextTerm,
                RecoveredValue.TextTermList {

    /**
     * Packed BKD point values for numerics / IP / date fields (Lucene 6+).
     * Caller decodes the first packed entry per the field's mapping type.
     */
    record PointBytes(List<byte[]> packed) implements RecoveredValue {}

    /**
     * Trie-encoded numeric value harvested from the inverted index (Lucene 4-5 / ES 1.x-2.x).
     * Floats and doubles arrive in their {@code sortableFloatBits} / {@code sortableDoubleBits}
     * long form; the caller applies the per-mapping decode.
     */
    record NumericTerm(long encoded) implements RecoveredValue {}

    /**
     * Indexed text recovered from the terms dictionary: either an exact single term
     * (boolean "T"/"F", keyword) or a position-ordered token concatenation for analyzed text.
     */
    record TextTerm(String text) implements RecoveredValue {}

    /**
     * Per-element analyzed text for multi-valued (array) text fields, recovered when the
     * position-increment gap between adjacent tokens reveals an array element boundary
     * (Lucene's {@code position_increment_gap}, default 100). Each list element is the
     * analyzed phrase that belonged to that array element. The list size equals the number
     * of array elements that contributed indexed tokens; elements that produced no tokens
     * (empty strings, all-stopword-filtered phrases) are not represented.
     */
    record TextTermList(List<String> texts) implements RecoveredValue {}
}
