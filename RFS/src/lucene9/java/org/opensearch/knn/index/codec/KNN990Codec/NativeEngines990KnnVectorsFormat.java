/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN990Codec;

import java.io.IOException;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.util.Bits;

/**
 * Minimal stub for reading KNN vector indices during migration.
 * Returns empty reader since we don't need to read vector data.
 */
@SuppressWarnings("java:S120") // Package name must match OpenSearch KNN plugin for Lucene codec SPI
public class NativeEngines990KnnVectorsFormat extends KnnVectorsFormat {
    private static final String FORMAT_NAME = "NativeEngines990KnnVectorsFormat";

    public NativeEngines990KnnVectorsFormat() {
        super(FORMAT_NAME);
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("Not supported for migration");
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        return EMPTY_VECTORS_READER;
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return 16000;
    }

    @SuppressWarnings("java:S1186") // Empty methods intentional - stub reader for migration
    private static final KnnVectorsReader EMPTY_VECTORS_READER = new KnnVectorsReader() {
        @Override
        public long ramBytesUsed() {
            return 0;
        }

        @Override
        public void close() {
            // Stub - no resources to close
        }

        @Override
        public void checkIntegrity() {
            // Stub - no integrity check needed for migration
        }

        @Override
        public FloatVectorValues getFloatVectorValues(String s) {
            return null;
        }

        @Override
        public ByteVectorValues getByteVectorValues(String s) {
            return null;
        }

        @Override
        public void search(String s, float[] floats, KnnCollector knnCollector, Bits bits) {
            // Stub - vector search not needed for migration
        }

        @Override
        public void search(String s, byte[] bytes, KnnCollector knnCollector, Bits bits) {
            // Stub - vector search not needed for migration
        }
    };
}
