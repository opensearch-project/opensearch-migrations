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
     * Used as fallback when doc_values and stored fields are not available.
     * @return List of byte arrays (packed point values) or null if not available
     */
    default java.util.List<byte[]> getPointValues(int docId, String fieldName) throws IOException { return null; }

}
