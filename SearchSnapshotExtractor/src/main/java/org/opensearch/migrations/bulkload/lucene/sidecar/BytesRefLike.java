package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.util.Arrays;
import java.util.Objects;

/**
 * Zero-dependency value type mirroring {@code org.apache.lucene.util.BytesRef} across the
 * version-boundary between per-Lucene-version sourcesets and the main (version-agnostic)
 * sourceset. Each per-version reader constructs one of these from its shadow-relocated
 * {@code BytesRef} and hands it to a {@link PostingsSink} — the main sourceset never
 * imports any Lucene type.
 *
 * <p>Semantics match {@code BytesRef}:
 *
 * <ul>
 *   <li>{@link #bytes()} returns the backing buffer directly — may be reused by the reader
 *       between calls, so long-lived retention requires {@link #toByteArray()}.
 *   <li>{@link #equals(Object)} / {@link #hashCode()} are value-based over the
 *       {@code [offset, offset+length)} subrange.
 * </ul>
 */
public final class BytesRefLike {

    private final byte[] bytes;
    private final int offset;
    private final int length;

    public BytesRefLike(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException(
                "Invalid subrange: offset=" + offset + " length=" + length + " bufferLength=" + bytes.length);
        }
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    /** Returns the backing buffer (no copy). Callers MUST NOT retain across the sink call. */
    public byte[] bytes() {
        return bytes;
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }

    /** Returns a defensive copy of the valid byte range — safe to retain indefinitely. */
    public byte[] toByteArray() {
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BytesRefLike)) return false;
        BytesRefLike other = (BytesRefLike) o;
        if (other.length != this.length) return false;
        return Arrays.equals(this.bytes, this.offset, this.offset + this.length,
                             other.bytes, other.offset, other.offset + other.length);
    }

    @Override
    public int hashCode() {
        // Match Arrays.hashCode(byte[]) semantics over the valid subrange so two instances
        // with equal bytes from different backing buffers compare-equal and hash-equal.
        int h = 1;
        for (int i = 0; i < length; i++) {
            h = 31 * h + bytes[offset + i];
        }
        return h;
    }
}
