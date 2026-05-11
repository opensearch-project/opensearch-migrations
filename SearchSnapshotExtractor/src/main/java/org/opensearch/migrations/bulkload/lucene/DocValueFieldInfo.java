package org.opensearch.migrations.bulkload.lucene;

/**
 * Represents field information for doc_values access.
 */
public interface DocValueFieldInfo {
    String name();
    DocValueType docValueType();
    
    /** Returns true if this field is a boolean type (detected via indexed T/F terms) */
    default boolean isBoolean() {
        return false;
    }

    /** Checks if field has only boolean terms (T/F) - used for boolean detection */
    static boolean hasOnlyBooleanTerms(Iterable<String> terms) {
        boolean hasTerms = false;
        for (String term : terms) {
            hasTerms = true;
            if (!"T".equals(term) && !"F".equals(term)) {
                return false;
            }
        }
        return hasTerms;
    }

    /** Single byte 'T' / 'F' — used for short-circuit boolean detection on raw term bytes. */
    byte BOOLEAN_TERM_T = (byte) 'T';
    byte BOOLEAN_TERM_F = (byte) 'F';
    
    enum DocValueType {
        NONE,
        NUMERIC,
        BINARY,
        SORTED,
        SORTED_NUMERIC,
        SORTED_SET
    }

    /** Simple implementation for use by all LeafReader versions */
    record Simple(String name, DocValueType docValueType, boolean isBoolean) implements DocValueFieldInfo {}
}
