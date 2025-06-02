package org.opensearch.migrations.bulkload.lucene.version_9;


import shadow.lucene9.org.apache.lucene.codecs.*;

public class ZSTD912CodecFallback extends Codec {

    public ZSTD912CodecFallback() {
        super("ZSTD912");
        System.out.println(">>>>> Loaded stub ZSTD912CodecFallback");
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return Codec.forName("Lucene912").storedFieldsFormat();
    }

    @Override public PostingsFormat postingsFormat() {
        return Codec.forName("Lucene912").postingsFormat();
    }
    @Override public DocValuesFormat docValuesFormat() {
        return Codec.forName("Lucene912").docValuesFormat();
    }
    @Override public TermVectorsFormat termVectorsFormat() {
        return Codec.forName("Lucene912").termVectorsFormat();
    }
    @Override public FieldInfosFormat fieldInfosFormat() {
        return Codec.forName("Lucene912").fieldInfosFormat();
    }
    @Override public SegmentInfoFormat segmentInfoFormat() {
        return new Lucene912CustomSegmentInfoFormat("ZSTD912", Mode.ZSTD_NO_DICT);
    }
    @Override public NormsFormat normsFormat() {
        return Codec.forName("Lucene912").normsFormat();
    }
    @Override public LiveDocsFormat liveDocsFormat() {
        return Codec.forName("Lucene912").liveDocsFormat();
    }
    @Override public CompoundFormat compoundFormat() {
        return Codec.forName("Lucene912").compoundFormat();
    }
    @Override public PointsFormat pointsFormat() {
        return Codec.forName("Lucene912").pointsFormat();
    }
    @Override public KnnVectorsFormat knnVectorsFormat() {
        return Codec.forName("Lucene912").knnVectorsFormat();
    }
}
