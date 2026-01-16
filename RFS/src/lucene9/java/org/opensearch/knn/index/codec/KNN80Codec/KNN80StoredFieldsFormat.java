/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * StoredFieldsFormat that handles zstd compression for OpenSearch 2.x KNN indices.
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import java.io.IOException;

import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;

/**
 * StoredFieldsFormat wrapper that handles zstd compression for OpenSearch 2.x indices.
 */
@SuppressWarnings("java:S120")
public class KNN80StoredFieldsFormat extends StoredFieldsFormat {
    private final StoredFieldsFormat delegate;
    private final StoredFieldsFormat zstdFormat;
    private final StoredFieldsFormat zstdNoDictFormat;

    public KNN80StoredFieldsFormat(StoredFieldsFormat delegate) {
        this.delegate = delegate;
        // Parameters: formatName, compressionMode, chunkSize, maxDocsPerChunk, blockShift
        this.zstdFormat = new Lucene90CompressingStoredFieldsFormat(
            "CustomStoredFieldsZstd", new ZstdCompressionMode(), 61440, 512, 10
        );
        this.zstdNoDictFormat = new Lucene90CompressingStoredFieldsFormat(
            "CustomStoredFieldsZstdNoDict", new ZstdNoDictCompressionMode(), 61440, 512, 10
        );
    }

    @Override
    public StoredFieldsReader fieldsReader(Directory directory, SegmentInfo si, FieldInfos fn, IOContext context) 
            throws IOException {
        var attrs = si.getAttributes();
        if (attrs != null) {
            for (var entry : attrs.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.contains("StoredFieldsFormat.mode")) {
                    if (value.equals("ZSTD_NO_DICT")) {
                        return zstdNoDictFormat.fieldsReader(directory, si, fn, context);
                    } else if (value.equals("ZSTD")) {
                        return zstdFormat.fieldsReader(directory, si, fn, context);
                    }
                }
            }
        }
        return delegate.fieldsReader(directory, si, fn, context);
    }

    @Override
    public StoredFieldsWriter fieldsWriter(Directory directory, SegmentInfo si, IOContext context) 
            throws IOException {
        return delegate.fieldsWriter(directory, si, context);
    }
}
