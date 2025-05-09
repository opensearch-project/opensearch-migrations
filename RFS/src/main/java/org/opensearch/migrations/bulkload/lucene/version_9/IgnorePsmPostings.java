package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Iterator;

import shadow.lucene9.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene9.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene9.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;
import shadow.lucene9.org.apache.lucene.index.Terms;
import shadow.lucene9.org.apache.lucene.store.Directory;

/**
 * PostingsFormat fallback for Elasticsearch 8.12+ segment formats.
 *
 * <p>This class provides a dummy implementation for "ES812Postings" to avoid runtime
 * errors when Lucene 9 attempts to load this postings format from snapshot-based
 * segment metadata during document migration.</p>
 *
 * <p>This migration assistant does not support reading real ES 8.x segment data that uses
 * proprietary `.psm` files. If any `.psm` file is detected, we fail fast with a clear error
 * message. Otherwise, an empty FieldsProducer is returned to simulate a no-op reader.</p>
 *
 * <p>Registered via Lucene's SPI to allow dynamic loading based on PostingsFormat name
 * stored in segment metadata.</p>
 *
 * <p><b>NOTE:</b> This class is intentionally limited to fallback behavior and not meant
 * to parse actual ES 8.x Lucene segments.</p>
 */
public class IgnorePsmPostings extends PostingsFormat {

    public IgnorePsmPostings() {
        super("ES812Postings");
    }

    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES812Postings is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        Directory dir = state.directory;
        for (String file : dir.listAll()) {
            if (file.endsWith(".psm")) {
                throw new UnsupportedOperationException(
                    "Detected .psm file in segment, which is not supported by the migration assistant. " +
                    "Your index may be using a newer/proprietary ES format. Migration cannot proceed."
                );
            }
        }
        return new FieldsProducer() {
            @Override public void close() {}
            @Override public void checkIntegrity() {}
            @Override public Iterator<String> iterator() {
                return java.util.Collections.emptyIterator();
            }
            @Override public Terms terms(String field) {
                return null;
            }
            @Override public int size() {
                return 0;
            }
        };
    }
}
