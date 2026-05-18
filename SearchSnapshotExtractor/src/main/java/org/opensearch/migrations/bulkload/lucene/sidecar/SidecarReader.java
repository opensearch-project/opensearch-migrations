package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import shadow.lucene10.org.apache.lucene.codecs.CodecUtil;
import shadow.lucene10.org.apache.lucene.codecs.lucene90.IndexedDISI;
import shadow.lucene10.org.apache.lucene.store.IOContext;
import shadow.lucene10.org.apache.lucene.store.IndexInput;
import shadow.lucene10.org.apache.lucene.store.MMapDirectory;
import shadow.lucene10.org.apache.lucene.store.RandomAccessInput;
import shadow.lucene10.org.apache.lucene.util.IOUtils;
import shadow.lucene10.org.apache.lucene.util.packed.DirectMonotonicReader;

/**
 * Reader for the single-container sidecar produced by {@link SidecarBuilder}.
 * MMap-backed; handles files &gt; 2 GiB via Lucene's {@link MMapDirectory}.
 *
 * <p>{@link #get(int)} clones IndexInputs per call so concurrent readers don't share
 * position state, matching the thread-safety contract {@link IndexInput} provides.
 */
public final class SidecarReader implements AutoCloseable {

    /** Size of the meta-start back-pointer that {@link SidecarBuilder} writes immediately
     *  before the {@code CodecUtil} footer. */
    private static final int META_BACK_POINTER_BYTES = Long.BYTES;

    private final int maxDoc;
    private final int numTerms;
    private final int numDocsWithValues;

    private final MMapDirectory dir;
    private final IndexInput container;

    private final DirectMonotonicReader termOffsets;
    private final DirectMonotonicReader docOffsets;

    // IndexedDISI — reconstructed per-get with the raw container input + offset/length.
    private final long disiStart;
    private final long disiLength;
    private final int disiJumpTableEntryCount;
    private final byte disiDenseRankPower;

    private volatile boolean closed;

    private SidecarReader(Builder b) {
        this.maxDoc = b.maxDoc;
        this.numTerms = b.numTerms;
        this.numDocsWithValues = b.numDocsWithValues;
        this.dir = b.dir;
        this.container = b.container;
        this.termOffsets = b.termOffsets;
        this.docOffsets = b.docOffsets;
        this.disiStart = b.disiStart;
        this.disiLength = b.disiLength;
        this.disiJumpTableEntryCount = b.disiJumpTableEntryCount;
        this.disiDenseRankPower = b.disiDenseRankPower;
    }

    static SidecarReader open(Path spillDir) throws IOException {
        MMapDirectory dir = new MMapDirectory(spillDir);
        IndexInput container = null;
        try {
            container = dir.openInput(SidecarBuilder.SIDECAR_FILE, IOContext.DEFAULT);
            CodecUtil.checkIndexHeader(container, SidecarBuilder.CODEC_NAME,
                    SidecarBuilder.VERSION_CURRENT, SidecarBuilder.VERSION_CURRENT,
                    SidecarBuilder.headerId(), "");

            long fileLen = container.length();
            long footerLen = CodecUtil.footerLength();
            long backPointerPos = fileLen - footerLen - META_BACK_POINTER_BYTES;
            if (backPointerPos < 0) {
                throw new IOException("Sidecar container shorter than expected back-pointer: " + fileLen);
            }
            container.seek(backPointerPos);
            long metaStart = container.readLong();
            if (metaStart < 0 || metaStart > backPointerPos) {
                throw new IOException("Sidecar container has malformed meta back-pointer: " + metaStart);
            }
            container.seek(metaStart);
            long termsStart        = container.readLong();
            long termsEnd          = container.readLong();
            long payloadsStart     = container.readLong();
            long payloadsEnd       = container.readLong();
            long termOffDataStart  = container.readLong();
            long termOffDataEnd    = container.readLong();
            long termOffMetaStart  = container.readLong();
            long termOffMetaEnd    = container.readLong();
            long docOffDataStart   = container.readLong();
            long docOffDataEnd     = container.readLong();
            long docOffMetaStart   = container.readLong();
            long docOffMetaEnd     = container.readLong();
            long disiStart         = container.readLong();
            long disiEnd           = container.readLong();
            int numTerms           = container.readVInt();
            int maxDoc             = container.readVInt();
            int numDocsWithValues  = container.readVInt();
            byte denseRankPower    = container.readByte();
            int disiJumpCount      = container.readVInt();
            int blockShift         = container.readVInt();

            // Header + footer checksum (written at build-time) are trusted — the sidecar was
            // just produced by buildAndOpenReader. A full-file checksum here would scan multi-GB
            // sidecars once per open and buys nothing beyond what checkIndexHeader already did.
            Builder b = new Builder();
            b.dir = dir;
            b.container = container;
            b.maxDoc = maxDoc;
            b.numTerms = numTerms;
            b.numDocsWithValues = numDocsWithValues;

            if (termsStart > termsEnd || payloadsStart > payloadsEnd) {
                throw new IOException("Sidecar container has malformed section offsets");
            }

            b.termOffsets = numTerms == 0 ? null
                    : loadMonotonic(container,
                            termOffDataStart, termOffDataEnd,
                            termOffMetaStart, termOffMetaEnd,
                            numTerms, blockShift, "termOffsets");

            b.docOffsets = numDocsWithValues == 0 ? null
                    : loadMonotonic(container,
                            docOffDataStart, docOffDataEnd,
                            docOffMetaStart, docOffMetaEnd,
                            numDocsWithValues, blockShift, "docOffsets");

            b.disiStart = disiStart;
            b.disiLength = disiEnd - disiStart;
            b.disiJumpTableEntryCount = disiJumpCount;
            b.disiDenseRankPower = denseRankPower;

            return new SidecarReader(b);
        } catch (Throwable t) {
            IOUtils.closeWhileHandlingException(container, dir);
            throw t;
        }
    }

    private static DirectMonotonicReader loadMonotonic(
            IndexInput container,
            long dataStart, long dataEnd, long metaStart, long metaEnd,
            long numValues, int blockShift, String name) throws IOException {
        IndexInput metaSlice = container.slice(name + "-meta", metaStart, metaEnd - metaStart);
        DirectMonotonicReader.Meta meta = DirectMonotonicReader.loadMeta(metaSlice, numValues, blockShift);
        RandomAccessInput dataSlice = container.randomAccessSlice(dataStart, dataEnd - dataStart);
        return DirectMonotonicReader.getInstance(meta, dataSlice);
    }

    /** Returns the tokens for {@code docId}, position-ordered, deduplicated by longest-at-same-position. */
    public List<TermEntry> get(int docId) throws IOException {
        if (closed) throw new IOException("SidecarReader closed");
        if (docId < 0 || docId >= maxDoc || numDocsWithValues == 0) return Collections.emptyList();

        long ordinal = findOrdinal(docId);
        if (ordinal < 0) return Collections.emptyList();

        return decodePayload(docOffsets.get(ordinal));
    }

    /** @return the DISI ordinal of {@code docId}, or -1 if the doc has no payload. */
    private long findOrdinal(int docId) throws IOException {
        IndexedDISI disi = new IndexedDISI(
                container.clone(), disiStart, disiLength, disiJumpTableEntryCount,
                disiDenseRankPower, numDocsWithValues);
        return disi.advance(docId) == docId ? disi.index() : -1L;
    }

    private List<TermEntry> decodePayload(long payloadOffset) throws IOException {
        IndexInput in = container.clone();
        in.seek(payloadOffset);
        int numEntries = in.readVInt();

        int[] pos      = new int[numEntries];
        int[] termIds  = new int[numEntries];
        int[] startOff = new int[numEntries];
        int[] endOff   = new int[numEntries];
        int prev = 0;
        for (int i = 0; i < numEntries; i++) {
            prev        += in.readVInt();
            pos[i]       = prev;
            termIds[i]   = in.readVInt();
            startOff[i]  = in.readZInt();
            endOff[i]    = in.readZInt();
        }
        return dedupByLongestTerm(pos, termIds, startOff, endOff);
    }

    /**
     * Within each position group, pick the winner by term length, then materialize
     * the winner's bytes. Singleton groups (the common case) read one term body;
     * multi-entry groups read one vInt length per candidate plus the winner's body.
     */
    private List<TermEntry> dedupByLongestTerm(
            int[] pos, int[] termIds, int[] startOff, int[] endOff) throws IOException {
        int n = pos.length;
        List<TermEntry> result = new ArrayList<>(n);
        int i = 0;
        while (i < n) {
            int j = i + 1;
            while (j < n && pos[j] == pos[i]) j++;
            int best = (j - i == 1) ? i : pickLongest(termIds, i, j);
            result.add(new TermEntry(readTerm(termIds[best]), startOff[best], endOff[best]));
            i = j;
        }
        return result;
    }

    private int pickLongest(int[] termIds, int from, int to) throws IOException {
        int best = from;
        int bestLen = readTermLength(termIds[from]);
        for (int k = from + 1; k < to; k++) {
            int lk = readTermLength(termIds[k]);
            if (lk > bestLen) { best = k; bestLen = lk; }
        }
        return best;
    }

    private int readTermLength(int termId) throws IOException {
        checkTermId(termId);
        IndexInput in = container.clone();
        in.seek(termOffsets.get(termId));
        return in.readVInt();
    }

    /** Convenience — strings only. */
    public List<String> getTermStrings(int docId) throws IOException {
        List<TermEntry> entries = get(docId);
        List<String> strings = new ArrayList<>(entries.size());
        for (TermEntry e : entries) strings.add(e.term());
        return strings;
    }

    private String readTerm(int termId) throws IOException {
        checkTermId(termId);
        IndexInput in = container.clone();
        in.seek(termOffsets.get(termId));
        int len = in.readVInt();
        byte[] bytes = new byte[len];
        in.readBytes(bytes, 0, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void checkTermId(int termId) throws IOException {
        if (termId < 0 || termId >= numTerms) {
            throw new IOException("termId out of range: " + termId + " (numTerms=" + numTerms + ")");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        IOUtils.closeWhileHandlingException(container, dir);
    }

    private static final class Builder {
        MMapDirectory dir;
        IndexInput container;
        DirectMonotonicReader termOffsets;
        DirectMonotonicReader docOffsets;
        long disiStart;
        long disiLength;
        int disiJumpTableEntryCount;
        byte disiDenseRankPower;
        int maxDoc;
        int numTerms;
        int numDocsWithValues;
    }
}
