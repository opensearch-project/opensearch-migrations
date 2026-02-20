package org.opensearch.migrations.bulkload.lucene;

public interface LuceneField {

    public String name();

    public String asUid();

    public String stringValue();

    public byte[] utf8Value();

    public String utf8ToStringValue();

}
