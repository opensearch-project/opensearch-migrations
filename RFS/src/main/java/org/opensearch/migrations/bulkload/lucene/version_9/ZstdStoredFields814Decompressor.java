package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import com.github.luben.zstd.Zstd;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Decompressor;
import shadow.lucene9.org.apache.lucene.store.DataInput;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

public class ZstdStoredFields814Decompressor extends Decompressor {

    @Override
    public void decompress(DataInput in, int originalLength, int offset, int length, BytesRef bytes) throws IOException {
        if (bytes.bytes.length < originalLength) {
            bytes.bytes = new byte[originalLength];
        }

        byte[] compressed = new byte[length];
        for (int i = 0; i < length; i++) {
            compressed[i] = in.readByte();
        }

        // Log first few bytes for debug
        System.out.printf(">>> Zstd decompress input size: %d, offset=%d, length=%d%n", compressed.length, offset, length);
        if (compressed.length >= 4) {
            System.out.printf(">>> First 4 bytes: %02x %02x %02x %02x%n", compressed[0], compressed[1], compressed[2], compressed[3]);
        }

        // Zstd decompress (standard method)
        long decompressedSize = Zstd.decompress(
                bytes.bytes, 0, bytes.bytes.length,
                compressed, 0, length
        );
        if (decompressedSize != originalLength) {
            throw new IOException("Unexpected decompressed size: " + decompressedSize + " != " + originalLength);
        }

        bytes.offset = 0;
        bytes.length = (int) decompressedSize;
    }

    @Override
    public Decompressor clone() {
        return new ZstdStoredFields814Decompressor();
    }
}
