package org.opensearch.migrations.bulkload.lucene.version_9;

import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsFormat;
import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsReader;
import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsWriter;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;

public class IgnoreES816BinaryQuantizedVectorsFormat extends KnnVectorsFormat {

    public IgnoreES816BinaryQuantizedVectorsFormat() {
        super("ES816BinaryQuantizedVectorsFormat");
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState ignored) {
        throw new UnsupportedOperationException("IgnoreVectorsFormat is read-only fallback");
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState ignored) {
        return FallbackLuceneComponents.EMPTY_VECTORS_READER;
    }
}
