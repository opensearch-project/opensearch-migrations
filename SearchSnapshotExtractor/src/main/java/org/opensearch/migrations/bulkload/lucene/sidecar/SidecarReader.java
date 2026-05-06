package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import shadow.lucene10.org.apache.lucene.store.IOContext;
import shadow.lucene10.org.apache.lucene.store.IndexInput;
import shadow.lucene10.org.apache.lucene.store.MMapDirectory;
import shadow.lucene10.org.apache.lucene.store.RandomAccessInput;
import shadow.lucene10.org.apache.lucene.util.IOUtils;

/**
 * Reads a sidecar built by {@link SidecarBuilder}. MMap-backed; supports files &gt; 2 GiB natively.
 * {@link #get(int)} clones the sidecar input so concurrent readers don't share position state.
 */
public final class SidecarReader implements AutoCloseable {

    /**
     * A single token with its character-offset span in the original field value.
     *
     * <p>{@code startOffset} and {@code endOffset} are {@link PostingsSink#NO_OFFSET} (-1) when
     * the field was not indexed with {@code index_options: offsets}. Callers must check for -1
     * before using offsets for gap-preserving reconstruction.
     */
    public record TermEntry(String term, int startOffset, int endOffset) {}

    private final Path spillDir;
    private final int maxDoc;
    private final int numTerms;

    private final MMapDirectory dir;
    private final IndexInput termsInput;
    private final IndexInput termOffsetsInput;
    private final IndexInput sidecarInput;
    private final IndexInput docIndexInput;
    private final RandomAccessInput termOffsetsRA;
    private final RandomAccessInput docIndexRA;

    private volatile boolean closed;

    private SidecarReader(Path spillDir, int maxDoc, int numTerms, MMapDirectory dir,
                          IndexInput termsInput, IndexInput termOffsetsInput,
                          IndexInput sidecarInput, IndexInput docIndexInput,
                          RandomAccessInput termOffsetsRA, RandomAccessInput docIndexRA) {
        this.spillDir = spillDir;
        this.maxDoc = maxDoc;
        this.numTerms = numTerms;
        this.dir = dir;
        this.termsInput = termsInput;
        this.termOffsetsInput = termOffsetsInput;
        this.sidecarInput = sidecarInput;
        this.docIndexInput = docIndexInput;
        this.termOffsetsRA = termOffsetsRA;
        this.docIndexRA = docIndexRA;
    }

    static SidecarReader open(Path spillDir, int maxDoc, int numTerms) throws IOException {
        MMapDirectory dir = new MMapDirectory(spillDir);
        IndexInput terms = null;
        IndexInput termOffsets = null;
        IndexInput sidecar = null;
        IndexInput docIndex = null;
        try {
            terms       = dir.openInput(SidecarBuilder.TERMS_FILE,        IOContext.DEFAULT);
            termOffsets = dir.openInput(SidecarBuilder.TERM_OFFSETS_FILE, IOContext.DEFAULT);
            sidecar     = dir.openInput(SidecarBuilder.SIDECAR_FILE,      IOContext.DEFAULT);
            docIndex    = dir.openInput(SidecarBuilder.DOC_INDEX_FILE,    IOContext.DEFAULT);
            return new SidecarReader(spillDir, maxDoc, numTerms, dir, terms, termOffsets, sidecar, docIndex,
                    termOffsets.randomAccessSlice(0L, termOffsets.length()),
                    docIndex.randomAccessSlice(0L, docIndex.length()));
        } catch (Throwable t) {
            IOUtils.closeWhileHandlingException(terms, termOffsets, sidecar, docIndex, dir);
            throw t;
        }
    }

    /**
     * Returns the tokens for {@code docId}, deduplicated by Lucene position.
     *
     * <p>When a token filter (e.g. {@code word_delimiter_graph}) emits multiple tokens at the
     * same position — a compound form and its sub-tokens — all share the same position in the
     * sidecar. We keep only the <em>longest</em> token per position, which is always the
     * unsplit original. For example, given {@code ("2000 09", pos=4)}, {@code ("2000", pos=4)},
     * and {@code ("09", pos=4)}, only {@code "2000 09"} is returned.
     *
     * <p>The returned list is in position-ascending order, matching original token order.
     */
    public List<TermEntry> get(int docId) throws IOException {
        if (closed) throw new IOException("SidecarReader closed");
        if (docId < 0 || docId >= maxDoc) return Collections.emptyList();
        long offset = docIndexRA.readLong(docId * 8L);
        if (offset == SidecarBuilder.DOC_INDEX_NO_TOKENS) return Collections.emptyList();
        if (offset < 0) throw new IOException("Sidecar offset out of range: " + offset);

        IndexInput in = sidecarInput.clone();
        in.seek(offset);
        int numEntries = in.readVInt();

        // First pass: read all raw entries, then dedup by position keeping longest.
        // We accumulate into a list of raw (pos, termId, startOff, endOff) tuples and
        // resolve term strings only after dedup to avoid redundant disk reads.
        int[] rawPos      = new int[numEntries];
        int[] rawTermId   = new int[numEntries];
        int[] rawStartOff = new int[numEntries];
        int[] rawEndOff   = new int[numEntries];
        int prevPos = 0;
        for (int i = 0; i < numEntries; i++) {
            prevPos          += in.readVInt();  // delta-encoded position
            rawPos[i]         = prevPos;
            rawTermId[i]      = in.readVInt();
            rawStartOff[i]    = in.readZInt();
            rawEndOff[i]      = in.readZInt();
        }

        // Dedup: for each position group, keep the entry whose term is longest.
        // The sidecar is sorted (docId, pos, termId) ASC so same-position entries are adjacent.
        // Resolve all term strings up front so the inner loop is a simple length comparison.
        String[] resolvedTerms = new String[numEntries];
        for (int k = 0; k < numEntries; k++) {
            resolvedTerms[k] = readTerm(rawTermId[k]);
        }

        List<TermEntry> result = new ArrayList<>(numEntries);
        int i = 0;
        while (i < numEntries) {
            int groupPos = rawPos[i];
            int best     = i;
            int j = i + 1;
            while (j < numEntries && rawPos[j] == groupPos) {
                if (resolvedTerms[j].length() > resolvedTerms[best].length()) {
                    best = j;
                }
                j++;
            }
            result.add(new TermEntry(resolvedTerms[best], rawStartOff[best], rawEndOff[best]));
            i = j;
        }
        return result;
    }

    /**
     * Convenience method — returns only the term strings, for callers that do not need
     * character offsets (e.g. existing callers prior to offset support).
     */
    public List<String> getTermStrings(int docId) throws IOException {
        List<TermEntry> entries = get(docId);
        List<String> strings = new ArrayList<>(entries.size());
        for (TermEntry e : entries) strings.add(e.term());
        return strings;
    }

    private String readTerm(int termId) throws IOException {
        if (termId < 0 || termId >= numTerms) {
            throw new IOException("termId out of range: " + termId + " (numTerms=" + numTerms + ")");
        }
        IndexInput in = termsInput.clone();
        in.seek(termOffsetsRA.readLong(termId * 8L));
        byte[] bytes = new byte[in.readVInt()];
        in.readBytes(bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        IOUtils.closeWhileHandlingException(termsInput, termOffsetsInput, sidecarInput, docIndexInput, dir);
    }

    Path spillDir() { return spillDir; }
}
