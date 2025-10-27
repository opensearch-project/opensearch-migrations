package org.opensearch.migrations.bulkload.lucene.version_8;

import shadow.lucene9.org.apache.lucene.codecs.Codec;
import shadow.lucene9.org.apache.lucene.codecs.CompoundFormat;
import shadow.lucene9.org.apache.lucene.codecs.DocValuesFormat;
import shadow.lucene9.org.apache.lucene.codecs.FieldInfosFormat;
import shadow.lucene9.org.apache.lucene.codecs.KnnVectorsFormat;
import shadow.lucene9.org.apache.lucene.codecs.LiveDocsFormat;
import shadow.lucene9.org.apache.lucene.codecs.NormsFormat;
import shadow.lucene9.org.apache.lucene.codecs.PointsFormat;
import shadow.lucene9.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene9.org.apache.lucene.codecs.SegmentInfoFormat;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.codecs.TermVectorsFormat;

// See below for the definitions of the strings used in this file
// https://github.com/opensearch-project/k-NN/blob/d7c55e9bdbc37d71a714569c9f37755053781c7d/src/main/java/org/opensearch/knn/index/codec/KNN87Codec/KNN87Codec.java#L59-L60
public class KNN87Codec extends Codec {

    public static final String BASE_CODEC_NAME = "Lucene87"; // Lucene Codec to be used

    public KNN87Codec() {
        super("KNN87Codec");
    }

    @Override
    public PostingsFormat postingsFormat() {
        return Codec.forName(BASE_CODEC_NAME).postingsFormat();
    }

    @Override
    public DocValuesFormat docValuesFormat() {
        return Codec.forName(BASE_CODEC_NAME).docValuesFormat();
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return new Elasticsearch814ZstdFieldsFormat();
    }

    @Override
    public TermVectorsFormat termVectorsFormat() {
        return Codec.forName(BASE_CODEC_NAME).termVectorsFormat();
    }

    @Override
    public FieldInfosFormat fieldInfosFormat() {
        return Codec.forName(BASE_CODEC_NAME).fieldInfosFormat();
    }

    @Override
    public SegmentInfoFormat segmentInfoFormat() {
        return Codec.forName(BASE_CODEC_NAME).segmentInfoFormat();
    }

    @Override
    public NormsFormat normsFormat() {
        return Codec.forName(BASE_CODEC_NAME).normsFormat();
    }

    @Override
    public LiveDocsFormat liveDocsFormat() {
        return Codec.forName(BASE_CODEC_NAME).liveDocsFormat();
    }

    @Override
    public CompoundFormat compoundFormat() {
        return Codec.forName(BASE_CODEC_NAME).compoundFormat();
    }

    @Override
    public PointsFormat pointsFormat() {
        return Codec.forName(BASE_CODEC_NAME).pointsFormat();
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return Codec.forName(BASE_CODEC_NAME).knnVectorsFormat();
    }
}
