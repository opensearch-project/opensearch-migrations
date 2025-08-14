package org.opensearch.migrations.bulkload.lucene;

import java.util.List;

public interface LuceneLiveDocs {

    boolean get(int docIdx);

    long length();

    LuceneLiveDocs xor(LuceneLiveDocs other);

    LuceneLiveDocs and(LuceneLiveDocs other);

    LuceneLiveDocs or(LuceneLiveDocs other);

    LuceneLiveDocs andNot(LuceneLiveDocs other);

    LuceneLiveDocs not();

    long andNotCount(LuceneLiveDocs other);

    long cardinality();

    List<Integer> getAllEnabledDocIdxs();
}
