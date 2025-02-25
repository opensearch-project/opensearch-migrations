package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.MyLeafReader;
import shadow.lucene7.org.apache.lucene.index.LeafReader;


import lombok.AllArgsConstructor;

@AllArgsConstructor
public class LeafReader7 implements MyLeafReader {

    private final LeafReader wrapped;

    public Document7 document(int luceneDocId) throws IOException {
        return new Document7(wrapped.document(luceneDocId));
    };
    
    public LiveDocs7 getLiveDocs() {
        return new LiveDocs7(wrapped.getLiveDocs());
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped;
    }

    public String getSegmentName() { return null; };

    public String getSegmentInfoString() { return null; };

}
