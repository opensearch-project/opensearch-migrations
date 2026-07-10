package org.opensearch.migrations.bulkload.lucene.version_10;

import shadow.lucene10.org.apache.lucene.codecs.Codec;
import shadow.lucene10.org.apache.lucene.codecs.CompoundFormat;
import shadow.lucene10.org.apache.lucene.codecs.DocValuesFormat;
import shadow.lucene10.org.apache.lucene.codecs.FieldInfosFormat;
import shadow.lucene10.org.apache.lucene.codecs.KnnVectorsFormat;
import shadow.lucene10.org.apache.lucene.codecs.LiveDocsFormat;
import shadow.lucene10.org.apache.lucene.codecs.NormsFormat;
import shadow.lucene10.org.apache.lucene.codecs.PointsFormat;
import shadow.lucene10.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene10.org.apache.lucene.codecs.SegmentInfoFormat;
import shadow.lucene10.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene10.org.apache.lucene.codecs.TermVectorsFormat;

/**
 * Read-only codec shim for segments written by Elasticsearch 9.x using the
 * {@code Elasticsearch900Lucene101} codec. ES 9.x writes segments whose SegmentInfo
 * records this codec name; without an SPI-registered shim, Lucene 10's
 * {@code Codec.forName("Elasticsearch900Lucene101")} throws and snapshot migration
 * fails at {@code SegmentInfos.readCodec()}.
 *
 * <p>Mirrors {@code version_9.Elasticsearch816CodecFallback} but targets Lucene 10:
 * <ul>
 *   <li>Base format {@code Lucene101} (forName-lookup name; the class lives in
 *       {@code backward_codecs.lucene101.Lucene101Codec}).</li>
 *   <li>StoredFields delegates to the ZSTD-backed
 *       {@link Elasticsearch814ZstdFieldsFormat} used by ES 8.14+/9.x.</li>
 * </ul>
 *
 * <p>The class FQN is registered in
 * {@code src/lucene10/resources/META-INF/services/org.apache.lucene.codecs.Codec} — ShadowJar
 * relocates the service-file name to {@code shadow.lucene10.org.apache.lucene.codecs.Codec}
 * when it packs the lucene10-shadow jar.
 */
public class Elasticsearch900Lucene101CodecFallback extends Codec {
    private static final String BASE_CODEC_NAME = "Lucene101";

    public Elasticsearch900Lucene101CodecFallback() {
        super("Elasticsearch900Lucene101");
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
