package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Collections;

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
    default java.util.List<byte[]> getPointValues(int docId, String fieldName) throws IOException { return null; }

    /**
     * Gets a field value by scanning the terms index. Very slow for fields with many unique values,
     * but viable for boolean fields (only 2 possible terms: T/F).
     */
    default String getValueFromTerms(int docId, String fieldName) throws IOException { return null; }

    /**
     * Fallback recovery: tries Points (for numerics/IP/date) or terms (for boolean).
     * Used when doc_values and stored fields are not available.
     */
    default java.util.Optional<Object> getValueFromPointsOrTerms(int docId, String fieldName, EsFieldType fieldType) throws IOException {
        return switch (fieldType) {
            case BOOLEAN -> {
                String term = getValueFromTerms(docId, fieldName);
                yield term != null ? java.util.Optional.of(term) : java.util.Optional.empty();
            }
            default -> {
                var points = getPointValues(docId, fieldName);
                yield (points != null && !points.isEmpty()) ? java.util.Optional.of(points) : java.util.Optional.empty();
            }
        };
    }

}
