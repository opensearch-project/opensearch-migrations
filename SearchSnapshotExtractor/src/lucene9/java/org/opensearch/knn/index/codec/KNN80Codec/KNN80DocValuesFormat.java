/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import java.io.IOException;

import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/**
 * Minimal DocValuesFormat that delegates to underlying format.
 */
@SuppressWarnings("java:S120") // Package name must match OpenSearch KNN plugin for Lucene codec SPI
public class KNN80DocValuesFormat extends DocValuesFormat {
    private final DocValuesFormat delegate;

    public KNN80DocValuesFormat(DocValuesFormat delegate) {
        super(delegate.getName());
        this.delegate = delegate;
    }

    @Override
    public DocValuesConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("Not supported for migration");
    }

    @Override
    public DocValuesProducer fieldsProducer(SegmentReadState state) throws IOException {
        return delegate.fieldsProducer(state);
    }
}
