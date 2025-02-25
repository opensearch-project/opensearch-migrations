package org.opensearch.migrations.bulkload.lucene.version_7;

import org.opensearch.migrations.bulkload.lucene.MyLiveDocs;
import shadow.lucene7.org.apache.lucene.util.Bits;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class LiveDocs7 implements MyLiveDocs {

    private final Bits wrapped;

    public boolean get(int docIdx) {
        return wrapped.get(docIdx);
    }
}
