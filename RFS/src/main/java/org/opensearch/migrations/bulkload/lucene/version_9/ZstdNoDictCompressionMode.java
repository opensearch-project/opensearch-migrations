package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shadow.lucene9.org.apache.lucene.codecs.compressing.CompressionMode;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Compressor;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Decompressor;
import shadow.lucene9.org.apache.lucene.store.ByteBuffersDataInput;
import shadow.lucene9.org.apache.lucene.store.DataInput;
import shadow.lucene9.org.apache.lucene.store.DataOutput;
import shadow.lucene9.org.apache.lucene.util.ArrayUtil;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

/** ZSTD Compression Mode (without a dictionary support). */
public class ZstdNoDictCompressionMode extends CompressionMode {

    private static final Logger logger = LogManager.getLogger(ZstdNoDictCompressionMode.class);
    private static final int NUM_SUB_BLOCKS = 10;

    private final int compressionLevel;

    /** default constructor */
    protected ZstdNoDictCompressionMode() {
        this.compressionLevel = Lucene912CustomCodec.DEFAULT_COMPRESSION_LEVEL;
    }

    /**
     * Creates a new instance with the given compression level.
     *
     * @param compressionLevel The compression level.
     */
    protected ZstdNoDictCompressionMode(int compressionLevel) {
        this.compressionLevel = compressionLevel;
    }

    /** Creates a new compressor instance. */
    @Override
    public Compressor newCompressor() {
        return new ZstdCompressor(compressionLevel);
    }

    /** Creates a new decompressor instance. */
    @Override
    public Decompressor newDecompressor() {
        return new ZstdDecompressor();
    }

    /** zstandard compressor */
    private static final class ZstdCompressor extends Compressor {

        private final int compressionLevel;
        private byte[] compressedBuffer;

        /** compressor with a given compresion level */
        public ZstdCompressor(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            compressedBuffer = BytesRef.EMPTY_BYTES;
        }

        private void compress(byte[] bytes, int offset, int length, DataOutput out) throws IOException {
            assert offset >= 0 : "offset value must be greater than 0";

            int blockLength = (length + NUM_SUB_BLOCKS - 1) / NUM_SUB_BLOCKS;
            out.writeVInt(blockLength);

            final int end = offset + length;
            assert end >= 0 : "buffer read size must be greater than 0";

            for (int start = offset; start < end; start += blockLength) {
                int l = Math.min(blockLength, end - start);

                if (l == 0) {
                    out.writeVInt(0);
                    return;
                }

                final int maxCompressedLength = (int) Zstd.compressBound(l);
                compressedBuffer = ArrayUtil.growNoCopy(compressedBuffer, maxCompressedLength);

                int compressedSize = (int) Zstd.compressByteArray(
                        compressedBuffer,
                        0,
                        compressedBuffer.length,
                        bytes,
                        start,
                        l,
                        compressionLevel
                );

                out.writeVInt(compressedSize);
                out.writeBytes(compressedBuffer, compressedSize);
            }
        }

        @Override
        public void compress(ByteBuffersDataInput buffersInput, DataOutput out) throws IOException {
            final int length = (int) buffersInput.length();
            byte[] bytes = new byte[length];
            buffersInput.readBytes(bytes, 0, length);
            compress(bytes, 0, length, out);
        }

        @Override
        public void close() throws IOException {}
    }

    /** zstandard decompressor */
    private static final class ZstdDecompressor extends Decompressor {

        private byte[] compressed;

        /** default decompressor */
        public ZstdDecompressor() {
            compressed = BytesRef.EMPTY_BYTES;
        }

        @Override
        public void decompress(DataInput in, int originalLength, int offset, int length, BytesRef bytes) throws IOException {
            assert offset + length <= originalLength : "buffer read size must be within limit";

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

                logger.debug(">>> Decompressing block at offset={} compressedLength={}", offsetInBlock, compressedLength);
                if (compressedLength >= 4) {
                    String headerHex = IntStream.range(0, 4)
                            .mapToObj(i -> String.format("0x%02X", compressed[i]))
                            .collect(Collectors.joining(" "));
                    logger.warn(">>> Frame header bytes: {}", headerHex);
                }

                // Log a hex dump of the entire compressed block
                byte[] blockCopy = Arrays.copyOf(compressed, compressedLength);
                String fullHexDump = IntStream.range(0, blockCopy.length)
                        .mapToObj(i -> String.format("%02X", blockCopy[i]))
                        .collect(Collectors.joining(" "));
                logger.warn(">>> Full compressed block (hex): {}", fullHexDump);

                // Optionally log Base64 as well
                String base64 = Base64.getEncoder().encodeToString(Arrays.copyOf(compressed, compressedLength));
                logger.warn(">>> Full compressed block (Base64): {}", base64);

                final int l = Math.min(blockLength, originalLength - offsetInBlock);
                bytes.bytes = ArrayUtil.grow(bytes.bytes, bytes.length + l);

                try {
                    final long uncompressed = Zstd.decompressByteArray(
                            bytes.bytes, bytes.length, l,
                            compressed, 0, compressedLength
                    );

                    if (uncompressed != l) {
                        throw new IOException("Zstd decompressed " + uncompressed + " bytes, but expected " + l + " at block offset " + offsetInBlock);
                    }

                    bytes.length += (int) uncompressed;
                } catch (ZstdException ex) {
                    throw new IOException("Failed to decompress Zstd block at offset=" + offsetInBlock +
                            ", compressedLen=" + compressedLength, ex);
                }

                offsetInBlock += blockLength;
            }

            bytes.offset = offsetInBytesRef;
            bytes.length = length;

            assert bytes.isValid() : "decompression output is corrupted.";
        }

        @Override
        public Decompressor clone() {
            return new ZstdDecompressor();
        }
    }
}
