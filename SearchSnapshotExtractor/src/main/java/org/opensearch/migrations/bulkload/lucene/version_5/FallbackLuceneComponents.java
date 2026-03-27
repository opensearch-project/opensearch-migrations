package org.opensearch.migrations.bulkload.lucene.version_5;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import shadow.lucene5.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene5.org.apache.lucene.index.Terms;
import shadow.lucene5.org.apache.lucene.util.Accountable;

/**
 * Shared fallback implementations for Lucene 5 codecs used in no-op PostingsFormat stubs.
 */
public class FallbackLuceneComponents {

    // Private constructor to prevent instantiation
    private FallbackLuceneComponents() {
        // Utility class
    }

    public static final FieldsProducer EMPTY_FIELDS_PRODUCER = new FieldsProducer() {
        @Override
        public long ramBytesUsed() {
            return 0;
        }

        @Override
        public void close() {
            // No resources to close in this fallback implementation
        }

        @Override
        public void checkIntegrity() {
            // Integrity check is skipped in this fallback stub
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

        @Override
        public Collection<Accountable> getChildResources() {
            return List.of();
        }
    };
}
