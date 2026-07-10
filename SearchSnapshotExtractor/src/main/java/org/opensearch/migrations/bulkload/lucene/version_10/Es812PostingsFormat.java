package org.opensearch.migrations.bulkload.lucene.version_10;

import java.io.IOException;

import shadow.lucene10.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene10.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene10.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene10.org.apache.lucene.index.SegmentReadState;
import shadow.lucene10.org.apache.lucene.index.SegmentWriteState;

/**
 * PostingsFormat fallback for Elasticsearch 8.12+ / 9.x segment formats on the
 * Lucene 10 shadow classpath.
 *
 * <p>Mirrors the version_9 {@code Es812PostingsFormat} pattern: when an ES 9.x
 * snapshot's segment metadata references the Elasticsearch-internal
 * {@code ES812Postings} postings format, Lucene's SPI needs a class of that
 * name to instantiate. Document reads only require stored fields
 * ({@code .fdt/.fdx}), not postings, so returning
 * {@link FallbackLuceneComponents#EMPTY_FIELDS_PRODUCER} lets segments open and
 * documents be extracted without supporting the proprietary
 * {@code .psm} side-file format.</p>
 *
 * <p>Registered via Lucene's SPI to allow dynamic loading based on
 * PostingsFormat name stored in segment metadata.</p>
 */
public class Es812PostingsFormat extends PostingsFormat {

    public Es812PostingsFormat() {
        super("ES812Postings");
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES812Postings is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        // We do not check for .psm files explicitly and silently ignore them.
        // Stored-field reads used by the snapshot reader do not require real postings.
        return FallbackLuceneComponents.EMPTY_FIELDS_PRODUCER;
    }
}
