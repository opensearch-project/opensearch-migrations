package org.opensearch.migrations.bulkload.lucene.version_5;

import org.opensearch.migrations.bulkload.lucene.LuceneLiveDocs;

import lombok.AllArgsConstructor;
import shadow.lucene5.org.apache.lucene.util.Bits;

@AllArgsConstructor
public class LiveDocs5 implements LuceneLiveDocs {

    private final Bits wrapped;

    public boolean get(int docIdx) {
        return wrapped.get(docIdx);
    }
}
