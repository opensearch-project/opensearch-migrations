package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Optional;

import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import lombok.AllArgsConstructor;
import shadow.lucene9.org.apache.lucene.index.FilterCodecReader;
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
        LeafReader reader = wrapped;

        // We wrap DirectoryReader with SoftDeletesDirectoryReaderWrapper when soft delete is possible.
        // If soft deletes are present on the segment, then SoftDeletesDirectoryReaderWrapper will
        // wrap SegmentReader in a FilterCodecReader, so we need to unwrap it first to access SegmentReader.
        if (reader instanceof FilterCodecReader) {
            reader =  FilterCodecReader.unwrap((FilterCodecReader) wrapped);
        }

        if (reader instanceof SegmentReader) {
            return Optional.of(((SegmentReader) reader));
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
