/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Zstd decompression mode (WITH dictionary) for OpenSearch 2.x KNN indices.
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import java.io.IOException;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictDecompress;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.compressing.Compressor;
import org.apache.lucene.codecs.compressing.Decompressor;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;

/**
 * Zstd WITH dictionary decompression mode for reading OpenSearch 2.x indices.
 */
@SuppressWarnings("java:S120")
public class ZstdCompressionMode extends CompressionMode {

    @Override
    public Compressor newCompressor() {
        throw new UnsupportedOperationException("Compression not supported");
    }

    @Override
    public Decompressor newDecompressor() {
        return new ZstdDictDecompressor();
    }

    private static final class ZstdDictDecompressor extends Decompressor {
        private byte[] compressedBuffer = BytesRef.EMPTY_BYTES;

        private void doDecompress(DataInput in, ZstdDecompressCtx dctx, BytesRef bytes, int decompressedLen) throws IOException {
            final int compressedLength = in.readVInt();
            if (compressedLength == 0) {
                return;
            }
            compressedBuffer = ArrayUtil.growNoCopy(compressedBuffer, compressedLength);
            in.readBytes(compressedBuffer, 0, compressedLength);

            bytes.bytes = ArrayUtil.grow(bytes.bytes, bytes.length + decompressedLen);
            int uncompressed = dctx.decompressByteArray(bytes.bytes, bytes.length, decompressedLen, compressedBuffer, 0, compressedLength);
            bytes.length += uncompressed;
        }

        @Override
        public void decompress(DataInput in, int originalLength, int offset, int length, BytesRef bytes) throws IOException {
            if (length == 0) {
                bytes.length = 0;
                return;
            }

            final int dictLength = in.readVInt();
            final int blockLength = in.readVInt();
            bytes.bytes = ArrayUtil.growNoCopy(bytes.bytes, dictLength);
            bytes.offset = bytes.length = 0;

            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                // decompress dictionary first
                doDecompress(in, dctx, bytes, dictLength);
                try (ZstdDictDecompress dictDecompress = new ZstdDictDecompress(bytes.bytes, 0, dictLength)) {
                    dctx.loadDict(dictDecompress);

                    int offsetInBlock = dictLength;
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
                        int l = Math.min(blockLength, originalLength - offsetInBlock);
                        doDecompress(in, dctx, bytes, l);
                        offsetInBlock += blockLength;
                    }

                    bytes.offset = offsetInBytesRef;
                    bytes.length = length;
                }
            }
        }

        @Override
        public Decompressor clone() {
            return new ZstdDictDecompressor();
        }
    }
}
