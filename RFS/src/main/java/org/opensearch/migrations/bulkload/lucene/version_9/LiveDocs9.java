package org.opensearch.migrations.bulkload.lucene.version_9;

import org.opensearch.migrations.bulkload.lucene.LuceneLiveDocs;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.util.Bits;

@AllArgsConstructor
public class LiveDocs9 implements LuceneLiveDocs {

    private final Bits wrapped;

    public boolean get(int docIdx) {
        return wrapped.get(docIdx);
    }
}
