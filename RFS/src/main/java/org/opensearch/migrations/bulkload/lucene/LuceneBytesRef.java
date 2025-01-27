package org.opensearch.migrations.bulkload.lucene;

public interface LuceneBytesRef {
    public byte[] bytes = new byte[0];
    public String utf8ToString();
}