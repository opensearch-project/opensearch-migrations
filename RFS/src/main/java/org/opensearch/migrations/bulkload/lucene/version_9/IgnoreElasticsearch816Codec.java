package org.opensearch.migrations.bulkload.lucene.version_9;

import shadow.lucene9.org.apache.lucene.codecs.*;

/**
 * Codec fallback for Elasticsearch 8.16+ segment formats.
 *
 * <p>This class provides a dummy implementation for "Elasticsearch816" to avoid runtime
 * errors when Lucene 9 attempts to load this postings format from snapshot-based
 * segment metadata during document migration.</p>
 *
 * <p>Registered via Lucene's SPI to allow dynamic loading based on Codec name
 * stored in segment metadata.</p>
 *
 */
public class IgnoreElasticsearch816Codec extends Codec {

    public IgnoreElasticsearch816Codec() {
        super("Elasticsearch816");
        System.out.println(">>>>> Loaded stub Codec for Elasticsearch816");
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
