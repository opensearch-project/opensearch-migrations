package org.opensearch.migrations.bulkload.lucene.version_10;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;

import lombok.AllArgsConstructor;
import shadow.lucene10.org.apache.lucene.index.LeafReaderContext;

@AllArgsConstructor
public class LeafReaderContext10 implements LuceneLeafReaderContext {

    private final LeafReaderContext wrapped;

    public LeafReader10 reader() {
        return new LeafReader10(wrapped.reader());
    }

}
