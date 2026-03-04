package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;

import com.github.luben.zstd.Zstd;
import shadow.lucene9.org.apache.lucene.codecs.compressing.CompressionMode;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Compressor;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Decompressor;
import shadow.lucene9.org.apache.lucene.store.DataInput;
import shadow.lucene9.org.apache.lucene.util.ArrayUtil;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

/** ZSTD Compression Mode (decompression only, no dictionary support). */
public class ES8CompatibleZstdNoDictCompressionMode extends CompressionMode {

    @Override
    public Compressor newCompressor() {
        throw new UnsupportedOperationException("Compression not supported");
    }

    @Override
    public Decompressor newDecompressor() {
        return new ZstdDecompressor();
    }

    private static final class ZstdDecompressor extends Decompressor {
        private byte[] compressed;
        public ZstdDecompressor() {
            compressed = BytesRef.EMPTY_BYTES;
        }

        public ZstdDecompressor(ZstdDecompressor original) {
            this.compressed = original.compressed.clone();
        }

        @Override
        public void decompress(DataInput in, int originalLength, int offset, int length, BytesRef bytes) throws IOException {
            assert offset + length <= originalLength : "buffer read size must be within limit";

            if (length == 0) {
                bytes.length = 0;
                return;
            }

            // Read all remaining compressed bytes assuming the entire input is one Zstd frame
            final int remaining = in.readVInt();
            compressed = ArrayUtil.growNoCopy(compressed, remaining); // allocate enough buffer
            in.readBytes(compressed, 0, remaining); // read the Zstd frame(s)

            // Prepare output buffer
            bytes.bytes = ArrayUtil.grow(bytes.bytes, originalLength);
            bytes.offset = 0;

            int decompressedSize = (int) Zstd.decompressByteArray(
                    bytes.bytes, 0, originalLength,
                    compressed, 0, remaining
            );

            if (decompressedSize != originalLength) {
                throw new IOException("Decompressed size mismatch: expected " + originalLength + " but got " + decompressedSize);
            }

            bytes.offset = offset;
            bytes.length = length;

            assert bytes.isValid() : "decompression output is corrupted.";
        }

        public Decompressor clone() {
            return new ZstdDecompressor(this);
        }
    }
}
