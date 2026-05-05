package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import lombok.extern.slf4j.Slf4j;

/**
 * Alternative {@code SidecarBuilder} using doc-banked streaming with a long-packed
 * per-doc sort — no external sort, no {@code postings.raw} scratch file.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>{@link #registerTerm} streams term bytes straight to {@code terms.dat} and writes
 *       the LE offset to {@code term-offsets.dat} — identical on-disk format to the baseline
 *       so the shared {@link SidecarReader} can consume both.</li>
 *   <li>{@link #accept} appends the {@code (position, termId)} pairs of each incoming tuple
 *       into a per-doc {@code int[]} bank (amortized doubling growth). The walk is term-major
 *       so the pairs arrive in term-order, not position-order.</li>
 *   <li>{@link #buildAndOpenReader} iterates docs in ASC order; for each non-empty doc it
 *       packs the pairs into a {@code long[]} where each element is
 *       {@code (position << 32) | (termId & 0xFFFFFFFFL)}, runs {@link Arrays#sort(long[])}
 *       (primitive dual-pivot quicksort, cache-friendly, no boxing), and emits the uvint-encoded
 *       {@code (numPairs, [posDelta, termId]...)} payload straight into {@code sidecar.dat}.
 *       The per-doc bank and packed long[] are released immediately after emit so peak off-heap
 *       live set shrinks as docs are consumed.</li>
 *   <li>{@code doc-index.dat} is a plain {@code long[maxDoc]} little-endian array of absolute
 *       offsets, or {@link #DOC_INDEX_NO_TOKENS} for empty docs.</li>
 * </ol>
 *
 * <p>Why it's faster than the three-pass baseline: only one disk write of the tuple data
 * (directly into the final sidecar), no {@code postings.raw}, no k-way merge. The per-doc
 * sort is over a primitive {@code long[]} whose natural order already matches
 * {@code (position ASC, termId ASC)} — no custom comparator, no indirect sort, no chunking.
 *
 * <p>Tradeoff: the per-doc banks live on heap between the walk and the emit, so peak heap is
 * {@code O(total_positions * 8 bytes)} plus array-growth overhead. That's the cost of skipping
 * external sort; it's acceptable for segments within a normal JVM heap budget.
 *
 * <p>Public API matches {@code SidecarBuilder} so call-sites can swap implementations.
 */
@Slf4j
public final class SidecarBuilder implements PostingsSink, AutoCloseable {
    /**
     * Retained for API compatibility with earlier {@code SidecarBuilder} revisions whose
     * spill-size floor calculations referenced it. This implementation has no fixed-width
     * tuple file, so the value only needs to remain a sane small positive power of two.
     */
    public static final int RECORD_BYTES = 12;

    /** Sentinel written into doc-index.dat for docs that have no tokens in this field. */
    static final long DOC_INDEX_NO_TOKENS = -1L;

    /** File names — package-private so {@link SidecarReader} can find them. */
    static final String TERMS_FILE        = "terms.dat";
    static final String TERM_OFFSETS_FILE = "term-offsets.dat";
    static final String SIDECAR_FILE      = "sidecar.dat";
    static final String DOC_INDEX_FILE    = "doc-index.dat";


    private final Path spillDir;
    private final int maxDoc;

    private final DataOutputStream termsOut;
    private final DataOutputStream termOffsetsOut;

    private final Path termsFile;
    private final Path termOffsetsFile;

    /** Per-doc packed (position, termId) int-pair bank, grown amortized; null == empty doc. */
    private final int[][] docEntries;
    /** Number of filled int slots in each doc's bank (always even — 2 ints per pair). */
    private final int[] docEntryCounts;

    private int nextTermId = 0;
    private int currentTermOffset = 0;
    private boolean built = false;
    private boolean closed = false;

    /**
     * @param spillDir                per-(segment,field) directory owned by this builder.
     * @param sortBufferBytes         accepted for API parity with prior builder; not used by this impl
     *                                (this implementation doesn't external-sort).
     * @param maxDoc                  the segment's {@code maxDoc}.
     */
    public SidecarBuilder(Path spillDir, int sortBufferBytes, int maxDoc) throws IOException {
        this.spillDir = spillDir;
        this.maxDoc = Math.max(1, maxDoc);
        Files.createDirectories(spillDir);
        this.termsFile = spillDir.resolve(TERMS_FILE);
        this.termOffsetsFile = spillDir.resolve(TERM_OFFSETS_FILE);
        this.termsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.termOffsetsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termOffsetsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.docEntries = new int[this.maxDoc][];
        this.docEntryCounts = new int[this.maxDoc];
    }

    @Override
    public int registerTerm(BytesRefLike term) throws IOException {
        int id = nextTermId++;
        // Little-endian int offset, matching SidecarBuilder so the shared reader can decode.
        termOffsetsOut.writeInt(Integer.reverseBytes(currentTermOffset));
        termsOut.writeInt(term.length());
        termsOut.write(term.bytes(), term.offset(), term.length());
        currentTermOffset += 4 + term.length();
        return id;
    }

    @Override
    public void accept(int termId, int docId, int[] positions, int positionCount) throws IOException {
        if (positionCount <= 0) return;
        if (docId < 0 || docId >= maxDoc) return; // out-of-range docs are silently ignored

        int[] bank = docEntries[docId];
        int w = docEntryCounts[docId];
        int needed = w + 2 * positionCount;
        if (bank == null) {
            int cap = 8;
            while (cap < needed) cap <<= 1;
            bank = new int[cap];
            docEntries[docId] = bank;
        } else if (bank.length < needed) {
            int cap = bank.length;
            while (cap < needed) cap <<= 1;
            bank = Arrays.copyOf(bank, cap);
            docEntries[docId] = bank;
        }
        for (int i = 0; i < positionCount; i++) {
            bank[w++] = positions[i];
            bank[w++] = termId;
        }
        docEntryCounts[docId] = w;
    }

    public SidecarReader buildAndOpenReader() throws IOException {
        if (built) throw new IllegalStateException("SidecarBuilder.buildAndOpenReader already called");
        built = true;

        termsOut.flush();
        termsOut.close();
        termOffsetsOut.flush();
        termOffsetsOut.close();

        Path sidecarFile = spillDir.resolve(SIDECAR_FILE);
        Path docIndexFile = spillDir.resolve(DOC_INDEX_FILE);

        long[] docOffsets = new long[maxDoc];
        Arrays.fill(docOffsets, DOC_INDEX_NO_TOKENS);

        try (FileChannel sideCh = FileChannel.open(sidecarFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long sidecarOffset = 0;
            // Reusable encode buffer; grown on demand to fit the largest single-doc payload.
            ByteBuffer perDoc = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            for (int docId = 0; docId < maxDoc; docId++) {
                int w = docEntryCounts[docId];
                if (w == 0) {
                    docEntries[docId] = null;
                    continue;
                }
                int[] bank = docEntries[docId];
                int numPairs = w >>> 1;

                long[] tmp = new long[numPairs];
                for (int i = 0; i < numPairs; i++) {
                    int pos = bank[2 * i];
                    int termId = bank[2 * i + 1];
                    tmp[i] = ((long) pos << 32) | (termId & 0xFFFFFFFFL);
                }
                // Free the per-doc bank; the long[] supersedes it for the remainder of this doc's work.
                docEntries[docId] = null;

                // Primitive dual-pivot quicksort by unsigned key; because pos is stored in the high
                // 32 bits and positions produced by Lucene are non-negative ints (< 2^31), natural
                // long ordering equals (position ASC, termId ASC) — matching the sidecar key.
                Arrays.sort(tmp);

                int maxBytes = 5 + numPairs * 10; // uvint header ≤5 + 2×uvint per pair ≤10
                if (perDoc.capacity() < maxBytes) {
                    int newCap = perDoc.capacity();
                    while (newCap < maxBytes) newCap <<= 1;
                    perDoc = ByteBuffer.allocate(newCap).order(ByteOrder.LITTLE_ENDIAN);
                }
                perDoc.clear();
                VarintCoder.writeUVInt(perDoc, numPairs);
                int prevPos = 0;
                for (long pair : tmp) {
                    int pos = (int) (pair >>> 32);
                    int termId = (int) pair;
                    VarintCoder.writeUVInt(perDoc, pos - prevPos);
                    VarintCoder.writeUVInt(perDoc, termId);
                    prevPos = pos;
                }
                perDoc.flip();
                docOffsets[docId] = sidecarOffset;
                int written = perDoc.remaining();
                while (perDoc.hasRemaining()) sideCh.write(perDoc);
                sidecarOffset += written;
            }
        }

        writeDocIndex(docIndexFile, docOffsets, maxDoc);
        closed = true;
        return SidecarReader.open(spillDir, maxDoc, nextTermId);
    }

    private static void writeDocIndex(Path docIndexDst, long[] docOffsets, int maxDoc) throws IOException {
        try (FileChannel ch = FileChannel.open(docIndexDst,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < maxDoc; i++) {
                if (!buf.hasRemaining()) {
                    buf.flip();
                    while (buf.hasRemaining()) ch.write(buf);
                    buf.clear();
                }
                buf.putLong(docOffsets[i]);
            }
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        // Abort path — only reached if buildAndOpenReader() wasn't called.
        closeQuietly(termsOut);
        closeQuietly(termOffsetsOut);
        tryDelete(termsFile);
        tryDelete(termOffsetsFile);
        closed = true;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            log.debug("Ignored close error during SidecarBuilder abort: {}", e.toString());
        }
    }

    private static void tryDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete spill file {}: {}", p, e.toString());
        }
    }
}
