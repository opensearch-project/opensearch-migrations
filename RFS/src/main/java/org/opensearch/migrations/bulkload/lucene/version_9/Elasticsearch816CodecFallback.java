package org.opensearch.migrations.bulkload.lucene.version_9;

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

/**
 * Codec fallback for Elasticsearch 8.16+ segment formats.
 */
public class Elasticsearch816CodecFallback extends Codec {

    public Elasticsearch816CodecFallback() {
        super("Elasticsearch816");
    }

    @Override
    public PostingsFormat postingsFormat() {
        return Codec.forName("Lucene912").postingsFormat();
    }

    @Override
    public DocValuesFormat docValuesFormat() {
        return Codec.forName("Lucene912").docValuesFormat();
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return Codec.forName("Lucene912").storedFieldsFormat();
    }

    @Override
    public TermVectorsFormat termVectorsFormat() {
        return Codec.forName("Lucene912").termVectorsFormat();
    }

    @Override
    public FieldInfosFormat fieldInfosFormat() {
        return Codec.forName("Lucene912").fieldInfosFormat();
    }

    @Override
    public SegmentInfoFormat segmentInfoFormat() {
        return Codec.forName("Lucene912").segmentInfoFormat();
    }

    @Override
    public NormsFormat normsFormat() {
        return Codec.forName("Lucene912").normsFormat();
    }

    @Override
    public LiveDocsFormat liveDocsFormat() {
        return Codec.forName("Lucene912").liveDocsFormat();
    }

    @Override
    public CompoundFormat compoundFormat() {
        return Codec.forName("Lucene912").compoundFormat();
    }

    @Override
    public PointsFormat pointsFormat() {
        return Codec.forName("Lucene912").pointsFormat();
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return Codec.forName("Lucene912").knnVectorsFormat();
    }
}
