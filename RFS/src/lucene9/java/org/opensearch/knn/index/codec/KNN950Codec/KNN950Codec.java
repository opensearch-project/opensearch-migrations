/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN950Codec;

import org.opensearch.knn.index.codec.KNN80Codec.KNN80CompoundFormat;
import org.opensearch.knn.index.codec.KNN80Codec.KNN80DocValuesFormat;
import org.opensearch.knn.index.codec.KNN80Codec.KNN80StoredFieldsFormat;
import org.opensearch.knn.index.codec.KNN990Codec.NativeEngines990KnnVectorsFormat;

import org.apache.lucene.backward_codecs.lucene95.Lucene95Codec;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.StoredFieldsFormat;

@SuppressWarnings("java:S120") // Package name must match OpenSearch KNN plugin for Lucene codec SPI
public class KNN950Codec extends FilterCodec {
    private static final String NAME = "KNN950Codec";
    public KNN950Codec() { super(NAME, new Lucene95Codec()); }
    @Override public DocValuesFormat docValuesFormat() { return new KNN80DocValuesFormat(delegate.docValuesFormat()); }
    @Override public CompoundFormat compoundFormat() { return new KNN80CompoundFormat(delegate.compoundFormat()); }
    @Override public KnnVectorsFormat knnVectorsFormat() { return new NativeEngines990KnnVectorsFormat(); }
    @Override public StoredFieldsFormat storedFieldsFormat() { return new KNN80StoredFieldsFormat(delegate.storedFieldsFormat()); }
}
