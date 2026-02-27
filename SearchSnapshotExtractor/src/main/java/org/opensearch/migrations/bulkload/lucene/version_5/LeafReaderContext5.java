package org.opensearch.migrations.bulkload.lucene.version_5;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;

import lombok.AllArgsConstructor;
import shadow.lucene5.org.apache.lucene.index.LeafReaderContext;

@AllArgsConstructor
public class LeafReaderContext5 implements LuceneLeafReaderContext {

    private final LeafReaderContext wrapped;

    public LeafReader5 reader() {
        return new LeafReader5(wrapped.reader());
    }

}
