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
     * Default implementation returns null for backward compatibility.
     */
    default Object getDocValue(int docId, DocValueFieldInfo fieldInfo) throws IOException {
        return null;
    }

    /**
     * Returns all indexed terms for a field, or null if field has no terms.
     * Used for detecting field types (e.g., boolean fields have only "T"/"F" terms).
     */
    default Iterable<String> getFieldTerms(String fieldName) throws IOException {
        return null;
    }

}
