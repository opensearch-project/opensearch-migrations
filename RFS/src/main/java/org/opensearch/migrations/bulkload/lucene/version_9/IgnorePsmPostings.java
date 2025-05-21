package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import shadow.lucene9.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene9.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene9.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;
import shadow.lucene9.org.apache.lucene.store.Directory;

/**
 * PostingsFormat fallback for Elasticsearch 8.12+ segment formats.
 *
 * <p>This class provides a dummy implementation for "ES812Postings"
 * when Lucene 9 attempts to load this postings format from snapshot-based
 * segment metadata during document migration.</p>
 *
 * <p>This migration assistant does not support reading real ES 8.x segment data that uses
 * proprietary `.psm` files. If any `.psm` file is detected, we fail fast with a clear error
 * message. Otherwise, an empty FieldsProducer is returned to simulate a no-op reader.</p>
 *
 * <p>Registered via Lucene's SPI to allow dynamic loading based on PostingsFormat name
 * stored in segment metadata.</p>
 *
 */
public class IgnorePsmPostings extends PostingsFormat {

    public IgnorePsmPostings() {
        super("ES812Postings");
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES812Postings is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        Directory dir = state.directory;
        for (String file : dir.listAll()) {
            if (file.endsWith(".psm")) {
                throw new UnsupportedOperationException(
                    String.format(
                        "Detected unsupported .psm file in segment [%s]. The index is using an unrecognized format.",
                        state.segmentInfo.name
                    )
                );
            }
        }
        return FallbackLuceneComponents.EMPTY_FIELDS_PRODUCER;
    }
}
