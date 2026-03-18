package org.opensearch.migrations.bulkload.lucene.version_9;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.index.LeafReaderContext;

@AllArgsConstructor
public class LeafReaderContext9 implements LuceneLeafReaderContext {

    private final LeafReaderContext wrapped;

    public LeafReader9 reader() {
        return new LeafReader9(wrapped.reader());
    }

}
