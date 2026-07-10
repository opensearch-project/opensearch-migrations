package org.opensearch.migrations.bulkload.lucene.version_10;

import java.io.IOException;

import shadow.lucene10.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene10.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene10.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene10.org.apache.lucene.index.SegmentReadState;
import shadow.lucene10.org.apache.lucene.index.SegmentWriteState;

/**
 * Bloom Filter PostingsFormat fallback for Elasticsearch 8.7+ and 9.x segment formats.
 *
 * <p>Registered via Lucene's SPI to allow dynamic loading based on PostingsFormat name
 * stored in segment metadata. Reading snapshots doesn't require the bloom filter itself;
 * we delegate to the underlying terms dictionary and ignore the filter bits.</p>
 */
public class IgnoreBloomFilter extends PostingsFormat {

    public IgnoreBloomFilter(String codecName) {
        super(codecName);
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        return FallbackLuceneComponents.EMPTY_FIELDS_PRODUCER;
    }
}
