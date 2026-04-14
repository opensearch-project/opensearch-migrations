package org.opensearch.migrations.bulkload.lucene.version_7;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;

import lombok.AllArgsConstructor;
import shadow.lucene7.org.apache.lucene.index.LeafReaderContext;

@AllArgsConstructor
public class LeafReaderContext7 implements LuceneLeafReaderContext {

    private final LeafReaderContext wrapped;

    public LeafReader7 reader() {
        return new LeafReader7(wrapped.reader());
    }

}
