package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.index.FilterCodecReader;
import shadow.lucene9.org.apache.lucene.index.LeafReader;
import shadow.lucene9.org.apache.lucene.index.SegmentReader;

@AllArgsConstructor
public class LeafReader9 implements LuceneLeafReader {

    private final LeafReader wrapped;

    public Document9 document(int luceneDocId) throws IOException {
        return new Document9(wrapped.storedFields().document(luceneDocId));
    }
    
    public LiveDocs9 getLiveDocs() {
        return wrapped.getLiveDocs() != null ? new LiveDocs9(wrapped.getLiveDocs()) : null;
    }

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public String getContextString() {
        return wrapped.getContext().toString();
    }

    private SegmentReader getSegmentReader() {
        var reader = wrapped;
        // FilterCodecReader is created when SoftDeletesDirectoryReaderWrapper encounters a segment with soft deletes
        if (reader instanceof FilterCodecReader) {
            reader = ((FilterCodecReader) reader).getDelegate();
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
}
