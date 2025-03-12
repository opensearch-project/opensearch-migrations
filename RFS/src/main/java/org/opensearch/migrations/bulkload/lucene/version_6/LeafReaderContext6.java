package org.opensearch.migrations.bulkload.lucene.version_6;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;

import lombok.AllArgsConstructor;
import shadow.lucene6.org.apache.lucene.index.LeafReaderContext;

@AllArgsConstructor
public class LeafReaderContext6 implements LuceneLeafReaderContext {

    private final LeafReaderContext wrapped;

    public LeafReader6 reader() {
        return new LeafReader6(wrapped.reader());
    }

}
