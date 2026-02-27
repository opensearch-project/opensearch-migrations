package org.opensearch.migrations.bulkload.lucene.version_5;

import java.io.IOException;

import shadow.lucene5.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene5.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene5.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene5.org.apache.lucene.index.SegmentReadState;
import shadow.lucene5.org.apache.lucene.index.SegmentWriteState;

/**
 * Stub codec to support ES 2.x completion090 segments.
 */
public class IgnoreCompletion090Postings extends PostingsFormat {

    public IgnoreCompletion090Postings() {
        super("completion090");
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("completion090 is a read-only fallback codec");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        return FallbackLuceneComponents.EMPTY_FIELDS_PRODUCER;
    }
}
