/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN940Codec;

import org.opensearch.knn.index.codec.KNN80Codec.KNN80CompoundFormat;
import org.opensearch.knn.index.codec.KNN80Codec.KNN80DocValuesFormat;
import org.opensearch.knn.index.codec.KNN990Codec.NativeEngines990KnnVectorsFormat;

import org.apache.lucene.backward_codecs.lucene94.Lucene94Codec;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;

@SuppressWarnings("java:S120") // Package name must match OpenSearch KNN plugin for Lucene codec SPI
public class KNN940Codec extends FilterCodec {
    private static final String NAME = "KNN940Codec";
    public KNN940Codec() { super(NAME, new Lucene94Codec()); }
    @Override public DocValuesFormat docValuesFormat() { return new KNN80DocValuesFormat(delegate.docValuesFormat()); }
    @Override public CompoundFormat compoundFormat() { return new KNN80CompoundFormat(delegate.compoundFormat()); }
    @Override public KnnVectorsFormat knnVectorsFormat() { return new NativeEngines990KnnVectorsFormat(); }
}
