package org.opensearch.migrations.bulkload.lucene.version_9;

import java.util.Collections;
import java.util.Iterator;

import shadow.lucene9.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene9.org.apache.lucene.index.Terms;

/**
 * Shared fallback implementations for Lucene codecs used in no-op PostingsFormat stubs.
 */
public class FallbackLuceneComponents {

    public static final FieldsProducer EMPTY_FIELDS_PRODUCER = new FieldsProducer() {
        @Override
        public void close() {
        }

        @Override
        public void checkIntegrity() {
        }

        @Override
        public Iterator<String> iterator() {
            return Collections.emptyIterator();
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
