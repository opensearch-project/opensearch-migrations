package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;
import java.util.Optional;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.AllArgsConstructor;
import shadow.lucene7.org.apache.lucene.index.LeafReader;
import shadow.lucene7.org.apache.lucene.index.SegmentCommitInfo;
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

    public String toString() {
        return wrapped.toString();
    }
}
