package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the {@code FileChannel.map} 2 GiB ceiling.
 *
 * <p>In production at ~80k terms, {@code sidecar.dat} grew past {@link Integer#MAX_VALUE}
 * and {@link SidecarReader#open} blew up with
 * {@code IllegalArgumentException: Size exceeds Integer.MAX_VALUE}. Additionally the
 * doc-index offsets are {@code long}, so offsets past the int boundary must be reachable
 * even on files ≤ 2 GiB can't provoke them.
 *
 * <p>Rather than drive enough real postings through {@link SidecarBuilder} to materialize
 * a 2+ GiB sidecar (infeasible in a unit test), this fabricates the on-disk format
 * directly: a sparse {@code sidecar.dat} with two known payloads — one at offset 0 and
 * one past the {@code int} boundary — plus matching {@code doc-index.dat},
 * {@code terms.dat}, and {@code term-offsets.dat} files.
 */
class SidecarReaderLargeFileTest {

    /** Offset deliberately past {@link Integer#MAX_VALUE} (2_147_483_647). */
    private static final long OFFSET_BEYOND_INT = 2_200_000_000L;

    @Test
    void opensAndReadsPayloadsBeyondTwoGigabytes(@TempDir Path tempDir) throws IOException {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill"));

        // Sparse sidecar.dat: ~2.2 GiB file, two tiny payloads at known offsets.
        // One encoded entry: uvint(1) uvint(0) uvint(0) = [0x01, 0x00, 0x00].
        byte[] payload = {0x01, 0x00, 0x00};
        Path sidecarFile = spillDir.resolve(SidecarBuilder.SIDECAR_FILE);
        writeSparseSidecar(sidecarFile, payload);

        // Skip on filesystems that would balloon to ~2.1 GiB non-sparse (rare; typical
        // ext4/xfs/tmpfs/APFS materialize the zeros lazily).
        long allocated = (long) Files.getAttribute(sidecarFile, "unix:size");
        long actualBlocks = safeUnixBlocks(sidecarFile);
        Assumptions.assumeTrue(
            actualBlocks == -1 || actualBlocks * 512L < allocated / 2,
            "Filesystem did not create a sparse file — skipping to avoid filling the disk");

        // doc-index.dat: long[2] LE, doc 0 at offset 0, doc 1 at the past-int offset.
        Path docIndexFile = spillDir.resolve(SidecarBuilder.DOC_INDEX_FILE);
        writeLongsLE(docIndexFile, 0L, OFFSET_BEYOND_INT);

        // terms.dat: one term "alpha" as VInt length + UTF-8 bytes (matches SidecarBuilder format).
        Path termsFile = spillDir.resolve(SidecarBuilder.TERMS_FILE);
        byte[] termBytes = "alpha".getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(termsFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            // termBytes.length = 5 fits in a single VInt byte.
            ByteBuffer buf = ByteBuffer.allocate(1 + termBytes.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.put((byte) termBytes.length);
            buf.put(termBytes);
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
        }

        // term-offsets.dat: one LE int64 = 0.
        Path termOffsetsFile = spillDir.resolve(SidecarBuilder.TERM_OFFSETS_FILE);
        try (FileChannel ch = FileChannel.open(termOffsetsFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(0L);
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
        }

        try (SidecarReader reader = SidecarReader.open(spillDir, /*maxDoc=*/2, /*numTerms=*/1)) {
            assertEquals(List.of("alpha"), reader.get(0), "payload at offset 0 should decode");
            assertEquals(List.of("alpha"), reader.get(1),
                "payload at offset " + OFFSET_BEYOND_INT + " (past Integer.MAX_VALUE) should decode");
        }
    }

    /**
     * Writes a sparse sidecar file: {@code payload} at offset 0 and again at
     * {@link #OFFSET_BEYOND_INT}. Resulting file size is at least
     * {@code OFFSET_BEYOND_INT + payload.length}, exceeding {@link Integer#MAX_VALUE}.
     */
    private static void writeSparseSidecar(Path file, byte[] payload) throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.position(0);
            ch.write(ByteBuffer.wrap(payload));
            ch.position(OFFSET_BEYOND_INT);
            ch.write(ByteBuffer.wrap(payload));
        }
    }

    private static void writeLongsLE(Path file, long... values) throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : values) buf.putLong(v);
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
        }
    }

    /** @return the {@code unix:blocks} attribute (512-byte blocks) or {@code -1} if unavailable. */
    private static long safeUnixBlocks(Path file) {
        try {
            Object v = Files.getAttribute(file, "unix:blocks");
            return v instanceof Number ? ((Number) v).longValue() : -1L;
        } catch (UnsupportedOperationException | IllegalArgumentException | UncheckedIOException | IOException e) {
            return -1L;
        }
    }
}
