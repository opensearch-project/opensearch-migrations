package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Optional;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.index.LeafReader;
import shadow.lucene9.org.apache.lucene.index.SegmentCommitInfo;
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

    private Optional<SegmentReader> getSegmentReader() {
        if (wrapped instanceof SegmentReader) {
            return Optional.of(((SegmentReader)wrapped));
        }
        return Optional.empty();
    }

    public String getSegmentName() { 
        return getSegmentReader()
            .map(SegmentReader::getSegmentName)
            .orElse(null);
    }

    public String getSegmentInfoString() {
        return getSegmentReader()
            .map(SegmentReader::getSegmentInfo)
            .map(SegmentCommitInfo::toString)
            .orElse(null);
    }
}
