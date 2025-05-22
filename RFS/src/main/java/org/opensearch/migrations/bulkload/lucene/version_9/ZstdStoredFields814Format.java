package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsReader;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsWriter;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.index.*;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.IOContext;

public class ZstdStoredFields814Format extends StoredFieldsFormat {

    public ZstdStoredFields814Format() {
        System.out.println(">>> Loaded ZstdStoredFields814Format as a fallback stored fields decoder.");
    }

    @Override
    public StoredFieldsReader fieldsReader(Directory directory, SegmentInfo si, FieldInfos fn, IOContext context) throws IOException {
        System.out.println(">>> Attempting to decode stored fields using ZstdStoredFields814Format for segment: " + si.name);
        // TODO: Replace with real reader implementation
        return new Lucene90CompressingStoredFieldsFormat(
                "ZstdStoredFields814",                         // Codec name (used in .fdt header)
                new ZstdStoredFields814CompressionMode(),      // Your custom mode
                10 * 48 * 1024,  // block size (same as Zstd)
                4096,            // max docs per block
                10               // block shift
        ).fieldsReader(directory, si, fn, context);
    }

    @Override
    public StoredFieldsWriter fieldsWriter(Directory directory, SegmentInfo si, IOContext context) throws IOException {
        throw new UnsupportedOperationException("ZstdStoredFields814Format: writing not supported");
    }
}
