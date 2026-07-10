package org.opensearch.migrations.bulkload.lucene.version_10;

import java.util.Collections;
import java.util.Iterator;

import shadow.lucene10.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene10.org.apache.lucene.codecs.KnnVectorsReader;
import shadow.lucene10.org.apache.lucene.index.ByteVectorValues;
import shadow.lucene10.org.apache.lucene.index.FloatVectorValues;
import shadow.lucene10.org.apache.lucene.index.Terms;
import shadow.lucene10.org.apache.lucene.search.AcceptDocs;
import shadow.lucene10.org.apache.lucene.search.KnnCollector;

/**
 * Shared fallback implementations for Lucene 9 codecs used in no-op PostingsFormat stubs.
 */
public class FallbackLuceneComponents {

    // Private constructor to prevent instantiation
    private FallbackLuceneComponents() {
        // Utility class
    }

    public static final KnnVectorsReader EMPTY_VECTORS_READER = new KnnVectorsReader() {

        @Override
        public void close() {
            // No resources to close in this fallback implementation
        }

        @Override
        public void checkIntegrity() {
            // Integrity check is skipped in this fallback stub
        }

        @Override
        public FloatVectorValues getFloatVectorValues(String s) {
            throw new UnsupportedOperationException("Should never be called");
        }

        @Override
        public ByteVectorValues getByteVectorValues(String s) {
            throw new UnsupportedOperationException("Should never be called");
        }

        @Override
        public void search(String s, float[] floats, KnnCollector knnCollector, AcceptDocs acceptDocs) {
            throw new UnsupportedOperationException("Should never be called");
        }

        @Override
        public void search(String s, byte[] bytes, KnnCollector knnCollector, AcceptDocs acceptDocs) {
            throw new UnsupportedOperationException("Should never be called");
        }
    };

    public static final FieldsProducer EMPTY_FIELDS_PRODUCER = new FieldsProducer() {
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
    };
}
