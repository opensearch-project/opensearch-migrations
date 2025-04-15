package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.AllArgsConstructor;
import shadow.lucene7.org.apache.lucene.index.FilterCodecReader;
import shadow.lucene7.org.apache.lucene.index.LeafReader;
import shadow.lucene7.org.apache.lucene.index.SegmentReader;

@AllArgsConstructor
public class LeafReader7 implements LuceneLeafReader {

    private final LeafReader wrapped;

    public Document7 document(int luceneDocId) throws IOException {
        return new Document7(wrapped.document(luceneDocId));
    }
    
    public LiveDocs7 getLiveDocs() {
        return wrapped.getLiveDocs() != null ? new LiveDocs7(wrapped.getLiveDocs()) : null;
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped.getContext().toString();
    }

    private SegmentReader getSegmentReader() {
        var reader = wrapped;
        if (reader instanceof FilterCodecReader) {
            return (SegmentReader) ((FilterCodecReader) reader).getDelegate();
        }
        if (reader instanceof SegmentReader) {
            return (SegmentReader) reader;
        }
        throw new IllegalStateException("Expected to extract SegmentReader but got " +
                reader.getClass() + " from " + wrapped.getClass());
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
