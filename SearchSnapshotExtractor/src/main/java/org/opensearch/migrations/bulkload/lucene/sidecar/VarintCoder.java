package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.nio.ByteBuffer;

/**
 * Little-endian varint (VByte) and zig-zag signed varint encode/decode over
 * {@link ByteBuffer}s with no Lucene or third-party dependency. Used by the sidecar
 * builder and reader to serialize term-string lengths, delta-doc deltas, delta-position
 * deltas, and anything else where the distribution is heavily biased toward small values.
 *
 * <p>Format for {@code writeUVInt}:
 *
 * <ul>
 *   <li>7 data bits per byte, low bit first, MSB set on every byte except the last.
 *   <li>Values in {@code [0, 128)} use 1 byte; {@code [128, 16384)} use 2 bytes; etc.
 * </ul>
 *
 * <p>Format for {@code writeZVInt}: zig-zag fold to unsigned ({@code (v << 1) ^ (v >> 31)})
 * then encode via {@code writeUVInt}. This puts small |v| into 1 byte regardless of sign.
 */
public final class VarintCoder {

    private VarintCoder() {}

    /**
     * Writes a non-negative int as an unsigned varint. Throws {@link IllegalArgumentException}
     * if the caller passes a negative value — the caller should use {@link #writeZVInt} for
     * values that can be negative.
     */
    public static void writeUVInt(ByteBuffer buf, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("writeUVInt requires non-negative value, got " + value);
        }
        writeUVIntUnchecked(buf, value);
    }

    /**
     * Internal writer that treats {@code value} as a 32-bit unsigned quantity — used by
     * {@link #writeZVInt} where the zig-zag fold can produce a bit pattern with the sign bit
     * set (e.g. {@code Integer.MIN_VALUE}). Uses unsigned shift so the loop terminates
     * regardless of sign.
     */
    private static void writeUVIntUnchecked(ByteBuffer buf, int value) {
        while ((value & 0xFFFF_FF80) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    /** Reads an unsigned varint from {@code buf} at its current position, advancing it. */
    public static int readUVInt(ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = buf.get();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 28) {
                // 5-byte varint cap: 5 * 7 = 35 bits, but a valid int needs at most 32 => shift <= 28.
                throw new IllegalStateException("Malformed varint: sequence exceeds 5 bytes");
            }
        }
    }

    /** Writes a signed int using zig-zag + varint encoding. */
    public static void writeZVInt(ByteBuffer buf, int value) {
        writeUVIntUnchecked(buf, (value << 1) ^ (value >> 31));
    }

    /** Reads a zig-zag signed varint from {@code buf}. */
    public static int readZVInt(ByteBuffer buf) {
        int raw = readUVInt(buf);
        return (raw >>> 1) ^ -(raw & 1);
    }

    /** Returns the number of bytes {@link #writeUVInt} would use to encode {@code value}. */
    public static int uvintByteCount(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("uvintByteCount requires non-negative value, got " + value);
        }
        return uvintByteCountUnchecked(value);
    }

    /** Unsigned-aware byte count, used internally by {@link #zvintByteCount}. */
    private static int uvintByteCountUnchecked(int value) {
        int count = 1;
        while ((value & 0xFFFF_FF80) != 0) {
            count++;
            value >>>= 7;
        }
        return count;
    }

    /** Returns the number of bytes {@link #writeZVInt} would use to encode {@code value}. */
    public static int zvintByteCount(int value) {
        return uvintByteCountUnchecked((value << 1) ^ (value >> 31));
    }
}
