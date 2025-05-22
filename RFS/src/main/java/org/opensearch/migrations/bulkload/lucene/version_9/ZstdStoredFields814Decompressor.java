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
        in.readBytes(compressed, 0, length);

        System.out.printf(">>> Zstd decompress input size: %d, offset=%d, length=%d%n", compressed.length, offset, length);
        if (compressed.length >= 4) {
            System.out.printf(">>> First 4 bytes: %02x %02x %02x %02x%n",
                    compressed[0], compressed[1], compressed[2], compressed[3]);
        }

        long actualSize = Zstd.decompress(bytes.bytes, 0, compressed, 0, compressed.length);
        if (actualSize != originalLength) {
            throw new IOException("Unexpected decompressed size: " + actualSize + " != " + originalLength);
        }

        bytes.offset = 0;
        bytes.length = (int) actualSize;
    }

    @Override
    public Decompressor clone() {
        return new ZstdStoredFields814Decompressor();
    }
}
