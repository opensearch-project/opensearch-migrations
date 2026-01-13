/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import org.apache.lucene.backward_codecs.lucene87.Lucene87Codec;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FilterCodec;

public class KNN80Codec extends FilterCodec {
    private static final String NAME = "KNN80Codec";
    public KNN80Codec() { super(NAME, new Lucene87Codec()); }
    @Override public DocValuesFormat docValuesFormat() { return new KNN80DocValuesFormat(delegate.docValuesFormat()); }
    @Override public CompoundFormat compoundFormat() { return new KNN80CompoundFormat(delegate.compoundFormat()); }
}
