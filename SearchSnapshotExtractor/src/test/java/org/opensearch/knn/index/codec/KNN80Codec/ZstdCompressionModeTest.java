/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.knn.index.codec.KNN80Codec;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import shadow.lucene9.org.apache.lucene.codecs.compressing.Decompressor;
import shadow.lucene9.org.apache.lucene.store.ByteArrayDataInput;
import shadow.lucene9.org.apache.lucene.store.ByteBuffersDataOutput;
import shadow.lucene9.org.apache.lucene.store.InputStreamDataInput;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ZstdCompressionModeTest {
    private static final int BLOCK_LENGTH = 64;
    private static final byte[] CONTENT =
        "{\"my_vector\":[0.1,0.2,0.3,0.4],\"title\":\"knn-zstd\"}\n".repeat(20).getBytes(StandardCharsets.UTF_8);

    static Stream<Arguments> readRanges() {
        return Stream.of(0, 64).flatMap(dictionaryLength -> Stream.of(
            arguments(dictionaryLength, 0, CONTENT.length),
            arguments(dictionaryLength, 4, 12),
            arguments(dictionaryLength, 48, 80),
            arguments(dictionaryLength, dictionaryLength + BLOCK_LENGTH, BLOCK_LENGTH),
            arguments(dictionaryLength, dictionaryLength + BLOCK_LENGTH - 1, 2),
            arguments(dictionaryLength, 196, 130),
            arguments(dictionaryLength, CONTENT.length - 17, 17)
        ));
    }

    @ParameterizedTest
    @MethodSource("readRanges")
    void decompressesStoredFields(int dictionaryLength, int offset, int length) throws IOException {
        byte[] encoded = encode(dictionaryLength);
        Decompressor decompressor = new ZstdCompressionMode().newDecompressor();
        BytesRef result = new BytesRef();

        decompressor.decompress(new ByteArrayDataInput(encoded), CONTENT.length, offset, length, result);

        assertArrayEquals(Arrays.copyOfRange(CONTENT, offset, offset + length),
            Arrays.copyOfRange(result.bytes, result.offset, result.offset + result.length));
    }

    @Test
    void releasesDictionaryWhenReadingFails() throws IOException {
        byte[] encoded = encode(64);
        Decompressor decompressor = new ZstdCompressionMode().newDecompressor();
        try (var input = new InputStreamDataInput(new ByteArrayInputStream(encoded, 0, encoded.length - 1))) {
            EOFException failure = assertThrows(EOFException.class,
                () -> decompressor.decompress(input, CONTENT.length, 0, CONTENT.length, new BytesRef()));

            assertEquals(0, failure.getSuppressed().length, "Dictionary cleanup must not fail after a read error");
        }
        assertCanReadAgain(decompressor, encoded);
    }

    @Test
    void releasesDictionaryWhenDecompressionFails() throws IOException {
        byte[] encoded = encode(64);
        byte[] corrupted = encoded.clone();
        var input = new ByteArrayDataInput(corrupted);
        input.readVInt(); // Dictionary length.
        input.readVInt(); // Block length.
        input.skipBytes(input.readVInt()); // Compressed dictionary.
        int compressedLength = input.readVInt();
        Arrays.fill(corrupted, input.getPosition(), input.getPosition() + compressedLength, (byte) 0);
        Decompressor decompressor = new ZstdCompressionMode().newDecompressor();

        ZstdException failure = assertThrows(ZstdException.class,
            () -> decompressor.decompress(new ByteArrayDataInput(corrupted), CONTENT.length, 0, CONTENT.length, new BytesRef()));

        assertEquals(0, failure.getSuppressed().length, "Dictionary cleanup must not fail after a decompression error");
        assertCanReadAgain(decompressor, encoded);
    }

    @Test
    void releasesContextWhenDictionaryDecompressionFails() throws IOException {
        byte[] encoded = encode(64);
        byte[] corrupted = encoded.clone();
        var input = new ByteArrayDataInput(corrupted);
        input.readVInt();
        input.readVInt();
        int compressedLength = input.readVInt();
        Arrays.fill(corrupted, input.getPosition(), input.getPosition() + compressedLength, (byte) 0);
        Decompressor decompressor = new ZstdCompressionMode().newDecompressor();

        ZstdException failure = assertThrows(ZstdException.class,
            () -> decompressor.decompress(new ByteArrayDataInput(corrupted), CONTENT.length, 0, CONTENT.length, new BytesRef()));

        assertEquals(0, failure.getSuppressed().length);
        assertCanReadAgain(decompressor, encoded);
    }

    static Stream<Arguments> shortBlocks() {
        return Stream.of(
            arguments(64, false), arguments(64, true),
            arguments(0, false), arguments(0, true)
        );
    }

    @ParameterizedTest
    @MethodSource("shortBlocks")
    void rejectsIncorrectDecodedLength(int dictionaryLength, boolean emptyFrame) throws IOException {
        var output = new ByteBuffersDataOutput();
        output.writeVInt(dictionaryLength);
        output.writeVInt(BLOCK_LENGTH);
        byte[] shortFrame = emptyFrame ? new byte[0] : Zstd.compress(new byte[] { 42 });
        writeBlock(output, dictionaryLength > 0 ? shortFrame : new byte[0]);
        if (dictionaryLength == 0) {
            writeBlock(output, shortFrame);
        }
        // A spare buffer must not turn a short valid Zstd frame into apparently valid stored data.
        BytesRef result = new BytesRef(new byte[128]);
        Arrays.fill(result.bytes, (byte) 0x55);
        Decompressor decompressor = new ZstdCompressionMode().newDecompressor();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> decompressor.decompress(new ByteArrayDataInput(output.toArrayCopy()), 64, 0, 64, result));

        assertEquals(0, failure.getSuppressed().length);
        assertCanReadAgain(decompressor, encode(64));
    }

    private static void assertCanReadAgain(Decompressor decompressor, byte[] encoded) throws IOException {
        BytesRef result = new BytesRef();
        decompressor.decompress(new ByteArrayDataInput(encoded), CONTENT.length, 0, CONTENT.length, result);
        assertArrayEquals(CONTENT, Arrays.copyOfRange(result.bytes, result.offset, result.offset + result.length));
    }

    private static byte[] encode(int dictionaryLength) throws IOException {
        // OpenSearch 2.x stores a compressed prefix dictionary followed by independently compressed blocks.
        var output = new ByteBuffersDataOutput();
        output.writeVInt(dictionaryLength);
        output.writeVInt(BLOCK_LENGTH);
        byte[] dictionary = Arrays.copyOf(CONTENT, dictionaryLength);
        writeBlock(output, dictionaryLength == 0 ? new byte[0] : Zstd.compress(dictionary));
        try (var context = new ZstdCompressCtx()) {
            context.loadDict(dictionary);
            for (int start = dictionaryLength; start < CONTENT.length; start += BLOCK_LENGTH) {
                byte[] block = Arrays.copyOfRange(CONTENT, start, Math.min(start + BLOCK_LENGTH, CONTENT.length));
                writeBlock(output, context.compress(block));
            }
        }
        return output.toArrayCopy();
    }

    private static void writeBlock(ByteBuffersDataOutput output, byte[] compressed) throws IOException {
        output.writeVInt(compressed.length);
        output.writeBytes(compressed, compressed.length);
    }
}
