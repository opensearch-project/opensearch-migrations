package org.opensearch.migrations.bulkload.lucene;

public interface LuceneField {

    public String name();

    public String asUid();

    public String stringValue();

    public String utf8ToStringValue();

    /** Returns numeric value if field is numeric, null otherwise */
    default Number numericValue() {
        return null;
    }

    /** Returns raw binary value as bytes, empty array if not binary */
    default byte[] binaryValue() {
        return new byte[0];
    }
}
