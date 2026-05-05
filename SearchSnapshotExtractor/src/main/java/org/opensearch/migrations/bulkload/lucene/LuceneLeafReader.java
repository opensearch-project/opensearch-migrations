package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.bulkload.lucene.sidecar.PostingsSink;

public interface LuceneLeafReader {

    public LuceneDocument document(int luceneDocId) throws IOException;

    public BitSetConverter.FixedLengthBitSet getLiveDocs();

    public int maxDoc();

    public String getContextString();

    public String getSegmentName();

    public String getSegmentInfoString();

    /**
     * Returns field information for all fields with doc_values.
     * Default implementation returns empty iterable for backward compatibility.
     */
    default Iterable<DocValueFieldInfo> getDocValueFields() {
        return Collections.emptyList();
    }

    /**
     * Gets the doc_value for a field at the given document ID.
     */
    default Object getDocValue(int docId, DocValueFieldInfo fieldInfo) throws IOException {
        String fieldName = fieldInfo.name();
        return switch (fieldInfo.docValueType()) {
            case NUMERIC -> getNumericValue(docId, fieldName);
            case SORTED -> getSortedValue(docId, fieldName);
            case SORTED_SET -> getSortedSetValues(docId, fieldName);
            case SORTED_NUMERIC -> getSortedNumericValues(docId, fieldName);
            case BINARY -> getBinaryValue(docId, fieldName);
            case NONE -> null;
        };
    }

    default Object getNumericValue(int docId, String fieldName) throws IOException { return null; }
    default Object getSortedValue(int docId, String fieldName) throws IOException { return null; }
    default Object getSortedSetValues(int docId, String fieldName) throws IOException { return null; }
    default Object getSortedNumericValues(int docId, String fieldName) throws IOException { return null; }
    default Object getBinaryValue(int docId, String fieldName) throws IOException { return null; }

    /**
     * Gets point values for a field at the given document ID.
     * @return List of byte arrays (packed point values) or null if not available
     */
    default List<byte[]> getPointValues(int docId, String fieldName) throws IOException { return null; }

    /**
     * Gets a field value by scanning the terms index. Very slow for fields with many unique values,
     * but viable for boolean fields (only 2 possible terms: T/F).
     */
    default String getValueFromTerms(int docId, String fieldName) throws IOException { return null; }

    /**
     * Streams the inverted index for {@code fieldName} into {@code sink} as
     * {@code (termId, docId, positions[])} callbacks, one per (term, doc) pair that has
     * at least one non-negative position.
     *
     * <p>Ordering contract: terms are visited in ascending-bytes order (matches Lucene's
     * {@code TermsEnum.next()}), and for each term docs are visited in ascending-docId order
     * (matches Lucene's {@code PostingsEnum.nextDoc()}). Positions within each callback are
     * already in ascending order as emitted by {@code PostingsEnum.nextPosition()}.
     *
     * <p>The default implementation is a no-op: versions that don't support source
     * reconstruction from the inverted index (e.g. Lucene 10 / OS 3.x which always have
     * stored fields or doc_values) leave this unimplemented and callers see an empty stream.
     *
     * <p>Callers must first invoke {@link PostingsSink#registerTerm} on each new term (as it
     * appears in the walk) to get its assigned {@code termId}, then invoke
     * {@link PostingsSink#accept(int, int, int[], int)} once per (term, doc). The reusable
     * {@code int[]} passed to {@code accept} is only borrowed for the duration of the call.
     */
    default void streamFieldPostings(String fieldName, PostingsSink sink) throws IOException {
        // no-op: default reader has no terms to stream.
    }

    /**
     * Version-specific hook: walk the terms dictionary for a trie-encoded numeric field (ES 1.x /
     * Lucene 4-5: long, int, double, float, date, ip) and return a docId -> decoded Long map.
     *
     * Lucene indexes each numeric value as a chain of prefix-coded terms across shift levels
     * (for range query support). Only the {@code shift==0} term carries the fully-precise value;
     * {@code NumericUtils.prefixCodedToLong} / {@code prefixCodedToInt} reverses the encoding.
     * Floats/doubles are stored as {@code sortableFloatBits}/{@code sortableDoubleBits} longs
     * and must be converted by the caller via the mapping type.
     *
     * Returning Long here keeps the interface version-agnostic; the final numeric type
     * (int vs long vs float vs double vs IP string) is applied in {@link SourceReconstructor}
     * using {@link FieldMappingInfo}.
     *
     * Called at most once per (segment, field) via {@link SegmentTermIndex}.
     */
    default Map<Integer, Long> buildNumericTermIndex(String fieldName) throws IOException {
        return Collections.emptyMap();
    }

    /**
     * Fallback recovery: tries Points (for numerics/IP/date), terms (for boolean),
     * or full term collection (for analyzed strings without stored fields).
     * Used when doc_values and stored fields are not available.
     *
     * @param termIndex per-segment term cache; may be null if the caller does not need
     *                  analyzed-string or numeric-term reconstruction.
     */
    default Optional<Object> getValueFromPointsOrTerms(int docId, String fieldName, EsFieldType fieldType,
                                                      SegmentTermIndex termIndex) throws IOException {
        return switch (fieldType) {
            case BOOLEAN -> {
                String term = getValueFromTerms(docId, fieldName);
                yield term != null ? Optional.of(term) : Optional.empty();
            }
            case STRING -> {
                // For analyzed string fields without stored fields or doc_values,
                // reconstruct a lossy version by collecting all indexed tokens in position order.
                // For keyword / "string"+index:not_analyzed fields (which are indexed with
                // IndexOptions.DOCS and therefore have no positions), fall back to
                // single-term recovery which preserves the exact original value.
                if (termIndex != null) {
                    try {
                        List<String> terms = termIndex.getTermsForDocument(this, docId, fieldName);
                        if (terms != null && !terms.isEmpty()) {
                            yield Optional.of(String.join(" ", terms));
                        }
                    } catch (UnsupportedOperationException e) {
                        // Field indexed without positions; fall through to single-term path.
                    }
                }
                String singleTerm = getValueFromTerms(docId, fieldName);
                yield singleTerm != null ? Optional.of(singleTerm) : Optional.empty();
            }
            case NUMERIC, UNSIGNED_LONG, SCALED_FLOAT, DATE, DATE_NANOS, IP -> {
                // First try Points (Lucene 6+ stores numerics as BKD-tree points).
                List<byte[]> points = getPointValues(docId, fieldName);
                if (points != null && !points.isEmpty()) {
                    yield Optional.of(points);
                }
                // Fall back to trie-encoded terms (Lucene 4-5 / ES 1.x-2.x).
                if (termIndex == null) {
                    yield Optional.empty();
                }
                Long numericVal = termIndex.getNumericForDocument(this, docId, fieldName);
                yield numericVal != null ? Optional.of(numericVal) : Optional.empty();
            }
            default -> {
                List<byte[]> points = getPointValues(docId, fieldName);
                yield (points != null && !points.isEmpty()) ? Optional.of(points) : Optional.empty();
            }
        };
    }

}
