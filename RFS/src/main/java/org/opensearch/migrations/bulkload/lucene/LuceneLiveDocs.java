package org.opensearch.migrations.bulkload.lucene;

public interface LuceneLiveDocs {

    public boolean get(int docIdx);

}
