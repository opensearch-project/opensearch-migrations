package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import shadow.lucene9.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene9.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene9.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;

/**
 * Bloom Filter PostingsFormat fallback for Elasticsearch 8.7+ segment formats.
 *
 *  <p>Registered via Lucene's SPI to allow dynamic loading based on PostingsFormat name
 *  stored in segment metadata.</p>
 *
 **/
public class IgnoreBloomFilter extends PostingsFormat {

    public IgnoreBloomFilter() {
        super("ES87BloomFilter");
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES87BloomFilter is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        return FallbackLuceneComponents.EMPTY_FIELDS_PRODUCER;
    }
}
