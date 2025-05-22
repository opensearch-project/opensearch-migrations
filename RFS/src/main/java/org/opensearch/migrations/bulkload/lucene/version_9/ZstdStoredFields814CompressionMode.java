package org.opensearch.migrations.bulkload.lucene.version_9;

import shadow.lucene9.org.apache.lucene.codecs.compressing.CompressionMode;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Compressor;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Decompressor;

public class ZstdStoredFields814CompressionMode extends CompressionMode {

    public ZstdStoredFields814CompressionMode() {
        System.out.println(">>> Loaded ZstdStoredFields814CompressionMode");
    }

    @Override
    public Compressor newCompressor() {
        throw new UnsupportedOperationException("Zstd compression not supported for writing.");
    }

    @Override
    public Decompressor newDecompressor() {
        return new ZstdStoredFields814Decompressor(); // You’ll implement this next
    }

    @Override
    public String toString() {
        return "ZstdStoredFields814CompressionMode";
    }
}
