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
 * PostingsFormat fallback for Elasticsearch 8.7+ segment formats.
 *
 * <p>This class provides a dummy implementation for "ES87BloomFilter" to avoid runtime
 * errors when Lucene 9 attempts to load this postings format from snapshot-based
 * segment metadata during document migration.</p>
 */
public class IgnoreBloomFilter extends PostingsFormat {

    public IgnoreBloomFilter() {
        super("ES87BloomFilter");
        System.out.println(">>>>> Loading stub IgnoreBloomFilter Class");
    }

    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES87BloomFilter is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        Directory dir = state.directory;
        for (String file : dir.listAll()) {
            if (file.endsWith(".psm")) {
                throw new UnsupportedOperationException(
                        "Detected Bloom Filter" +
                        "Your index may be using a newer/proprietary ES format. Migration cannot proceed."
                );
            }
        }
        return new FieldsProducer() {
            @Override
            public void close() {
            }

            @Override
            public void checkIntegrity() {
            }

            @Override
            public Iterator<String> iterator() {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public Terms terms(String field) {
                return null;
            }

            @Override
            public int size() {
                return 0;
            }
        };
    }
}
