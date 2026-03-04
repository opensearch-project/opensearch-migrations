package org.opensearch.migrations.bulkload.lucene.version_9;

import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsFormat;
import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsReader;
import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsWriter;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;

/**
 * PostingsFormat fallback for Elasticsearch 8.14+ vector formats.
 */
public class IgnoreES816HnswBinaryQuantizedVectorsFormat extends KnnVectorsFormat {

    public IgnoreES816HnswBinaryQuantizedVectorsFormat() {
        super("ES816HnswBinaryQuantizedVectorsFormat");
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
