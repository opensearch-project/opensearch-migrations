package org.opensearch.migrations.bulkload.lucene;

public interface LuceneDocField {
    public String name();
    public LuceneBytesRef binaryValue();
    public String stringValue();
}