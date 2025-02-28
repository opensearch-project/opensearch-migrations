package org.opensearch.migrations.bulkload.lucene.version_6;

import org.opensearch.migrations.bulkload.lucene.LuceneLiveDocs;

import lombok.AllArgsConstructor;
import shadow.lucene6.org.apache.lucene.util.Bits;

@AllArgsConstructor
public class LiveDocs6 implements LuceneLiveDocs {

    private final Bits wrapped;

    public boolean get(int docIdx) {
        return wrapped.get(docIdx);
    }
}
