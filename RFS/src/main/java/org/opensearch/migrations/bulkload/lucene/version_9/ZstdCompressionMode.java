package org.opensearch.index.codec.customcodecs;

import java.io.IOException;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import shadow.lucene9.org.apache.lucene.codecs.compressing.CompressionMode;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Compressor;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Decompressor;
import shadow.lucene9.org.apache.lucene.store.ByteBuffersDataInput;
import shadow.lucene9.org.apache.lucene.store.DataInput;
import shadow.lucene9.org.apache.lucene.store.DataOutput;
import shadow.lucene9.org.apache.lucene.util.ArrayUtil;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

/** Zstandard Compression Mode */
public class ZstdCompressionMode extends CompressionMode {

    private static final int NUM_SUB_BLOCKS = 10;
    private static final int DICT_SIZE_FACTOR = 6;
    private static final int DEFAULT_COMPRESSION_LEVEL = 3;

    private final int compressionLevel;

    /** default constructor */
    public ZstdCompressionMode() {
        this.compressionLevel = DEFAULT_COMPRESSION_LEVEL;
    }

    /** parameterized constructor */
    public ZstdCompressionMode(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    @Override
    public Compressor newCompressor() {
        return new ZstdCompressor(compressionLevel);
    }

    @Override
    public Decompressor newDecompressor() {
        return new ZstdDecompressor();
    }

    private static final class ZstdCompressor extends Compressor {
        private final int compressionLevel;
        private byte[] compressedBuffer;

        public ZstdCompressor(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            this.compressedBuffer = BytesRef.EMPTY_BYTES;
        }

        private void doCompress(byte[] bytes, int offset, int length, ZstdCompressCtx cctx, DataOutput out) throws IOException {
            if (length == 0) {
                out.writeVInt(0);
                return;
            }

            int maxCompressedLength = (int) Zstd.compressBound(length);
            compressedBuffer = ArrayUtil.growNoCopy(compressedBuffer, maxCompressedLength);

            int compressedSize = cctx.compressByteArray(
                    compressedBuffer, 0, compressedBuffer.length,
                    bytes, offset, length
            );

            out.writeVInt(compressedSize);
            out.writeBytes(compressedBuffer, compressedSize);
        }

        private void compress(byte[] bytes, int offset, int length, DataOutput out) throws IOException {
            int dictLength = length / (NUM_SUB_BLOCKS * DICT_SIZE_FACTOR);
            int blockLength = (length - dictLength + NUM_SUB_BLOCKS - 1) / NUM_SUB_BLOCKS;

            out.writeVInt(dictLength);
            out.writeVInt(blockLength);

            int end = offset + length;

            try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
                cctx.setLevel(compressionLevel);

                doCompress(bytes, offset, dictLength, cctx, out);

                try (ZstdDictCompress dict = new ZstdDictCompress(bytes, offset, dictLength, compressionLevel)) {
                    cctx.loadDict(dict);

                    for (int start = offset + dictLength; start < end; start += blockLength) {
                        int l = Math.min(blockLength, end - start);
                        doCompress(bytes, start, l, cctx, out);
                    }
                }
            }
        }

        @Override
        public void compress(ByteBuffersDataInput input, DataOutput out) throws IOException {
            int length = (int) input.length();
            byte[] bytes = new byte[length];
            input.readBytes(bytes, 0, length);
            compress(bytes, 0, length, out);
        }

        @Override
        public void close() {}
    }

    private static final class ZstdDecompressor extends Decompressor {
        private byte[] compressedBuffer;

        public ZstdDecompressor() {
            compressedBuffer = BytesRef.EMPTY_BYTES;
        }

        private void doDecompress(DataInput in, ZstdDecompressCtx dctx, BytesRef bytes, int expectedLen) throws IOException {
            int compressedLen = in.readVInt();
            if (compressedLen == 0) return;

            compressedBuffer = ArrayUtil.growNoCopy(compressedBuffer, compressedLen);
            in.readBytes(compressedBuffer, 0, compressedLen);

            bytes.bytes = ArrayUtil.grow(bytes.bytes, bytes.length + expectedLen);
            int actualLen = dctx.decompressByteArray(bytes.bytes, bytes.length, expectedLen, compressedBuffer, 0, compressedLen);

            if (expectedLen != actualLen) {
                throw new IOException("Decompressed size mismatch: expected=" + expectedLen + " actual=" + actualLen);
            }

            bytes.length += actualLen;
        }

        @Override
        public void decompress(DataInput in, int originalLength, int offset, int length, BytesRef bytes) throws IOException {
            if (length == 0) {
                bytes.length = 0;
                return;
            }

            int dictLength = in.readVInt();
            int blockLength = in.readVInt();

            bytes.bytes = ArrayUtil.growNoCopy(bytes.bytes, dictLength);
            bytes.offset = bytes.length = 0;

            try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
                doDecompress(in, dctx, bytes, dictLength);

                try (ZstdDictDecompress dict = new ZstdDictDecompress(bytes.bytes, 0, dictLength)) {
                    dctx.loadDict(dict);

                    int offsetInBlock = dictLength;
                    int offsetInBytesRef = offset;

                    while (offsetInBlock + blockLength < offset) {
                        int skipLen = in.readVInt();
                        in.skipBytes(skipLen);
                        offsetInBlock += blockLength;
                        offsetInBytesRef -= blockLength;
                    }

                    while (offsetInBlock < offset + length) {
                        int l = Math.min(blockLength, originalLength - offsetInBlock);
                        doDecompress(in, dctx, bytes, l);
                        offsetInBlock += blockLength;
                    }

                    bytes.offset = offsetInBytesRef;
                    bytes.length = length;

                    assert bytes.isValid() : "Invalid decompressed BytesRef";
                }
            }
        }

        @Override
        public Decompressor clone() {
            return new ZstdDecompressor();
        }
    }
}