package org.opensearch.migrations.bulkload.lucene.version_5;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.AllArgsConstructor;
import shadow.lucene5.org.apache.lucene.index.LeafReader;
import shadow.lucene5.org.apache.lucene.index.SegmentReader;

@AllArgsConstructor
public class LeafReader5 implements LuceneLeafReader {

    private final LeafReader wrapped;

    public Document5 document(int luceneDocId) throws IOException {
        return new Document5(wrapped.document(luceneDocId));
    }

    public LiveDocs5 getLiveDocs() {
        return wrapped.getLiveDocs() != null ? new LiveDocs5(wrapped.getLiveDocs()) : null;
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped.getContext().toString();
    }

    private SegmentReader getSegmentReader() {
        if (wrapped instanceof SegmentReader) {
            return (SegmentReader) wrapped;
        }
        throw new IllegalStateException("Expected SegmentReader but got " + wrapped.getClass());
    }

    public String getSegmentName() {
        return getSegmentReader()
                .getSegmentName();
    }

    public String getSegmentInfoString() {
        return getSegmentReader()
                .getSegmentInfo()
                .toString();
    }

    public String toString() {
        return wrapped.toString();
    }
}
