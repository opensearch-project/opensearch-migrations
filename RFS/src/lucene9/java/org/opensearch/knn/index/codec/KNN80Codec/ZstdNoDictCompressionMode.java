/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Zstd decompression mode (NO dictionary) for OpenSearch 2.x KNN indices.
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import java.io.IOException;

import com.github.luben.zstd.Zstd;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.compressing.Compressor;
import org.apache.lucene.codecs.compressing.Decompressor;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;

/**
 * Zstd NO-DICT decompression mode for reading OpenSearch 2.x indices.
 */
@SuppressWarnings("java:S120")
public class ZstdNoDictCompressionMode extends CompressionMode {

    @Override
    public Compressor newCompressor() {
        throw new UnsupportedOperationException("Compression not supported");
    }

    @Override
    public Decompressor newDecompressor() {
        return new ZstdNoDictDecompressor();
    }

    private static final class ZstdNoDictDecompressor extends Decompressor {
        private byte[] compressed = BytesRef.EMPTY_BYTES;

        @Override
        public void decompress(DataInput in, int originalLength, int offset, int length, BytesRef bytes) throws IOException {
            if (length == 0) {
                bytes.length = 0;
                return;
            }

            final int blockLength = in.readVInt();
            bytes.offset = bytes.length = 0;
            int offsetInBlock = 0;
            int offsetInBytesRef = offset;

            // Skip unneeded blocks
            while (offsetInBlock + blockLength < offset) {
                final int compressedLength = in.readVInt();
                in.skipBytes(compressedLength);
                offsetInBlock += blockLength;
                offsetInBytesRef -= blockLength;
            }

            // Read blocks that intersect with the interval we need
            while (offsetInBlock < offset + length) {
                final int compressedLength = in.readVInt();
                if (compressedLength == 0) {
                    return;
                }
                compressed = ArrayUtil.growNoCopy(compressed, compressedLength);
                in.readBytes(compressed, 0, compressedLength);

                final int l = Math.min(blockLength, originalLength - offsetInBlock);
                bytes.bytes = ArrayUtil.grow(bytes.bytes, bytes.length + l);

                int uncompressed = (int) Zstd.decompressByteArray(bytes.bytes, bytes.length, l, compressed, 0, compressedLength);
                bytes.length += uncompressed;
                offsetInBlock += blockLength;
            }

            bytes.offset = offsetInBytesRef;
            bytes.length = length;
        }

        @Override
        public Decompressor clone() {
            return new ZstdNoDictDecompressor();
        }
    }
}
