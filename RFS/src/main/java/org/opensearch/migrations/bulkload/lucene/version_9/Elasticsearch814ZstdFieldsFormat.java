package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import lombok.NoArgsConstructor;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsReader;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsWriter;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.index.FieldInfos;
import shadow.lucene9.org.apache.lucene.index.SegmentInfo;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.IOContext;

@NoArgsConstructor
public class Elasticsearch814ZstdFieldsFormat extends StoredFieldsFormat {
    public static final String MODE_KEY = "Zstd814StoredFieldsFormat.mode";

    public StoredFieldsReader fieldsReader(Directory directory, SegmentInfo si, FieldInfos fn, IOContext context) throws IOException {
        String value = si.getAttribute(MODE_KEY);
        if (value == null) {
            throw new IllegalStateException("missing value for " + MODE_KEY + " for segment: " + si.name);
        } else {
            Mode mode = Mode.valueOf(value);
            return this.impl(mode).fieldsReader(directory, si, fn, context);
        }
    }

    public StoredFieldsWriter fieldsWriter(Directory directory, SegmentInfo si, IOContext context) throws IOException {
        throw new UnsupportedOperationException("Elasticsearch8CompatibleFieldsFormat does not support writing stored fields");
    }

    StoredFieldsFormat impl(Mode mode) {
        if (Mode.BEST_COMPRESSION.equals(mode)) {
            // Arbitrary values, kept lucene defaults
            return new Lucene90CompressingStoredFieldsFormat("ZstdStoredFields814", new ES8CompatibleZstdNoDictCompressionMode(), 491520, 4096, 10);
        }
        throw new AssertionError();
    }

    public enum Mode {
        BEST_COMPRESSION;
    }
}
