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

    public List<String> get(int docId) throws IOException {
        if (closed) throw new IOException("SidecarReader closed");
        if (docId < 0 || docId >= maxDoc) return Collections.emptyList();
        long offset = docIndexRA.readLong(docId * 8L);
        if (offset == SidecarBuilder.DOC_INDEX_NO_TOKENS) return Collections.emptyList();
        if (offset < 0) throw new IOException("Sidecar offset out of range: " + offset);

        IndexInput in = sidecarInput.clone();
        in.seek(offset);
        int numEntries = in.readVInt();
        List<String> result = new ArrayList<>(numEntries);
        for (int i = 0; i < numEntries; i++) {
            in.readVInt(); // pos delta — list order preserves positional order
            result.add(readTerm(in.readVInt()));
        }
        return result;
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
