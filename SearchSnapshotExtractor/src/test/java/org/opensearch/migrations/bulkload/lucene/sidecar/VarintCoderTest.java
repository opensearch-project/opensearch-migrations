package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trip and boundary-value tests for {@link VarintCoder}.
 *
 * <p>Correctness of the sidecar format depends entirely on this pair of routines: a reader
 * that decodes a varint differently from how the builder encoded it will produce garbage
 * terms. Tests cover signed/unsigned ranges, boundary bytes, and dense fuzzing.
 */
class VarintCoderTest {

    @Test
    void unsignedRoundTrip_smallValues() {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int[] values = {0, 1, 2, 127, 128, 129, 16_383, 16_384, 16_385,
                        2_097_151, 2_097_152, Integer.MAX_VALUE};
        for (int v : values) VarintCoder.writeUVInt(buf, v);
        buf.flip();
        for (int v : values) assertEquals(v, VarintCoder.readUVInt(buf));
    }

    @Test
    void signedZigzag_roundTripsPositiveAndNegative() {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int[] values = {0, 1, -1, 2, -2, 63, -63, 64, -64,
                        Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int v : values) VarintCoder.writeZVInt(buf, v);
        buf.flip();
        for (int v : values) assertEquals(v, VarintCoder.readZVInt(buf));
    }

    @Test
    void fuzzUnsigned_roundTrip() {
        Random rng = new Random(0xC0FFEEL);
        int[] values = new int[10_000];
        for (int i = 0; i < values.length; i++) values[i] = rng.nextInt() & 0x7FFF_FFFF;
        ByteBuffer buf = ByteBuffer.allocate(values.length * 5);
        for (int v : values) VarintCoder.writeUVInt(buf, v);
        buf.flip();
        for (int v : values) assertEquals(v, VarintCoder.readUVInt(buf));
    }

    @Test
    void fuzzSigned_roundTrip() {
        Random rng = new Random(0xDEADBEEFL);
        int[] values = new int[10_000];
        for (int i = 0; i < values.length; i++) values[i] = rng.nextInt();
        ByteBuffer buf = ByteBuffer.allocate(values.length * 5);
        for (int v : values) VarintCoder.writeZVInt(buf, v);
        buf.flip();
        for (int v : values) assertEquals(v, VarintCoder.readZVInt(buf));
    }

    @Test
    void uvint_rejectsNegative() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        assertThrows(IllegalArgumentException.class, () -> VarintCoder.writeUVInt(buf, -1));
    }

    @Test
    void byteCount_matchesEncodedLength() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        VarintCoder.writeUVInt(buf, 1_000_000);
        assertEquals(buf.position(), VarintCoder.uvintByteCount(1_000_000));
        buf.clear();
        VarintCoder.writeZVInt(buf, -500_000);
        assertEquals(buf.position(), VarintCoder.zvintByteCount(-500_000));
    }

    @Test
    void truncatedBuffer_throwsWithClearError() {
        // Write a 3-byte varint then truncate to 1 byte in the read side.
        ByteBuffer src = ByteBuffer.allocate(8);
        VarintCoder.writeUVInt(src, 300_000); // 3 bytes
        src.flip();
        ByteBuffer truncated = ByteBuffer.allocate(1);
        truncated.put(src.get());
        truncated.flip();
        assertThrows(java.nio.BufferUnderflowException.class, () -> VarintCoder.readUVInt(truncated));
    }
}
