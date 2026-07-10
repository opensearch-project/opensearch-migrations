package org.opensearch.migrations.bulkload.lucene.version_10;

import java.io.IOException;

import shadow.lucene10.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene10.org.apache.lucene.codecs.StoredFieldsReader;
import shadow.lucene10.org.apache.lucene.codecs.StoredFieldsWriter;
import shadow.lucene10.org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;
import shadow.lucene10.org.apache.lucene.index.FieldInfos;
import shadow.lucene10.org.apache.lucene.index.SegmentInfo;
import shadow.lucene10.org.apache.lucene.store.Directory;
import shadow.lucene10.org.apache.lucene.store.IOContext;

/**
 * StoredFieldsFormat shim for segments written by Elasticsearch 8.14+ / 9.x with the
 * ZSTD-backed "Zstd814StoredFieldsFormat" stored-fields writer. Read-only — this codec
 * is only used to decompress stored fields during snapshot migration.
 *
 * <p>Mirrors {@code version_9.Elasticsearch814ZstdFieldsFormat} but targets Lucene 10 APIs.
 */
public class Elasticsearch814ZstdFieldsFormat extends StoredFieldsFormat {
    public static final String MODE_KEY = "Zstd814StoredFieldsFormat.mode";

    public Elasticsearch814ZstdFieldsFormat() {
        // no-op
    }

    @Override
    public StoredFieldsReader fieldsReader(Directory directory, SegmentInfo si, FieldInfos fn, IOContext context)
            throws IOException {
        String value = si.getAttribute(MODE_KEY);
        if (value == null) {
            throw new IllegalStateException("missing value for " + MODE_KEY + " for segment: " + si.name);
        }
        Mode mode = Mode.valueOf(value);
        return impl(mode).fieldsReader(directory, si, fn, context);
    }

    @Override
    public StoredFieldsWriter fieldsWriter(Directory directory, SegmentInfo si, IOContext context) {
        throw new UnsupportedOperationException(
                "Elasticsearch814ZstdFieldsFormat does not support writing stored fields");
    }

    StoredFieldsFormat impl(Mode mode) {
        if (Mode.BEST_COMPRESSION.equals(mode)) {
            // Same chunk/block/maxDocs parameters as the Lucene 9 shim — these are the
            // values Elasticsearch uses when writing segments with Zstd814StoredFieldsFormat.
            return new Lucene90CompressingStoredFieldsFormat(
                    "ZstdStoredFields814",
                    new ES8CompatibleZstdNoDictCompressionMode(),
                    491520, 4096, 10);
        }
        throw new AssertionError();
    }

    public enum Mode {
        BEST_COMPRESSION
    }
}
