package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Version-specific hook: walk the terms dictionary for {@code fieldName} once and return a
     * docId -> position-ordered term list map. This performs the raw Lucene iteration over
     * shadow-relocated {@code Terms}/{@code TermsEnum}/{@code PostingsEnum}, which is why it
     * can't be written in this shared interface.
     *
     * Called at most once per (segment, field) via {@link SegmentTermIndex}. The returned map
     * is owned by the caller and lives only as long as the {@link SegmentTermIndex} that holds
     * it, which is scoped to a single segment's Flux in {@link LuceneReader#readDocsFromSegment}.
     */
    default Map<Integer, List<String>> buildTermPositionIndex(String fieldName) throws IOException {
        return Collections.emptyMap();
    }

    /**
     * Fallback recovery: tries Points (for numerics/IP/date), terms (for boolean),
     * or full term collection (for analyzed strings without stored fields).
     * Used when doc_values and stored fields are not available.
     *
     * @param termIndex per-segment term cache; may be null if the caller does not need
     *                  analyzed-string reconstruction (falls back to empty).
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
                if (termIndex == null) {
                    yield Optional.empty();
                }
                List<String> terms = termIndex.getTermsForDocument(this, docId, fieldName);
                yield (terms != null && !terms.isEmpty())
                    ? Optional.of(String.join(" ", terms))
                    : Optional.empty();
            }
            default -> {
                List<byte[]> points = getPointValues(docId, fieldName);
                yield (points != null && !points.isEmpty()) ? Optional.of(points) : Optional.empty();
            }
        };
    }

}
