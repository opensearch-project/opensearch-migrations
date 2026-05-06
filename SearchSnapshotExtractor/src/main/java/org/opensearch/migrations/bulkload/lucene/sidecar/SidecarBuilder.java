package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import lombok.extern.slf4j.Slf4j;
import shadow.lucene10.org.apache.lucene.codecs.CodecUtil;
import shadow.lucene10.org.apache.lucene.store.ByteBuffersDataOutput;
import shadow.lucene10.org.apache.lucene.store.Directory;
import shadow.lucene10.org.apache.lucene.store.IOContext;
import shadow.lucene10.org.apache.lucene.store.IndexOutput;
import shadow.lucene10.org.apache.lucene.store.NIOFSDirectory;
import shadow.lucene10.org.apache.lucene.util.BitUtil;
import shadow.lucene10.org.apache.lucene.util.BytesRef;
import shadow.lucene10.org.apache.lucene.util.IOUtils;
import shadow.lucene10.org.apache.lucene.util.OfflineSorter;
import shadow.lucene10.org.apache.lucene.util.OfflineSorter.BufferSize;

/** Builds per-(segment, field) sidecar via {@link OfflineSorter}; emits terms/term-offsets/sidecar/doc-index. */
@Slf4j
public final class SidecarBuilder implements PostingsSink, AutoCloseable {

    /** docId(4) + pos(4) + termId(4), big-endian so unsigned bytewise sort = (doc, pos, term) ASC. */
    public static final int RECORD_BYTES = 12;
    static final long DOC_INDEX_NO_TOKENS = -1L;

    static final String TERMS_FILE        = "terms.dat";
    static final String TERM_OFFSETS_FILE = "term-offsets.dat";
    static final String SIDECAR_FILE      = "sidecar.dat";
    static final String DOC_INDEX_FILE    = "doc-index.dat";
    private static final String SORT_INPUT_FILE = "sort-input.bin";
    private static final int DEFAULT_SORT_BUFFER_MB = 256;
    private static final VarHandle VH_BE_INT = BitUtil.VH_BE_INT;

    private final Path spillDir;
    private final Directory dir;
    private final int maxDoc;
    private final BufferSize sortBufferSize;

    private final IndexOutput termsOut;
    private final IndexOutput termOffsetsOut;
    private final IndexOutput sortInputOut;
    private final OfflineSorter.ByteSequencesWriter sortInputWriter;
    private final byte[] recordScratch = new byte[RECORD_BYTES];

    private int nextTermId = 0;
    private boolean built = false;
    private boolean closed = false;

    public SidecarBuilder(Path spillDir, long sortBufferBytes, int maxDoc) throws IOException {
        this.spillDir = spillDir;
        this.maxDoc = Math.max(1, maxDoc);
        Files.createDirectories(spillDir);
        this.dir = new NIOFSDirectory(spillDir);

        // BufferSize.megabytes takes int MiB. Clamp to [MIN_BUFFER_SIZE_MB, Integer.MAX_VALUE].
        long mb = sortBufferBytes > 0 ? (sortBufferBytes >>> 20) : DEFAULT_SORT_BUFFER_MB;
        mb = Math.max(OfflineSorter.MIN_BUFFER_SIZE_MB, Math.min(Integer.MAX_VALUE, mb));
        this.sortBufferSize = BufferSize.megabytes((int) mb);

        this.termsOut        = dir.createOutput(TERMS_FILE, IOContext.DEFAULT);
        this.termOffsetsOut  = dir.createOutput(TERM_OFFSETS_FILE, IOContext.DEFAULT);
        this.sortInputOut    = dir.createOutput(SORT_INPUT_FILE, IOContext.DEFAULT);
        this.sortInputWriter = new OfflineSorter.ByteSequencesWriter(sortInputOut);
    }

    @Override
    public int registerTerm(BytesRefLike term) throws IOException {
        termOffsetsOut.writeLong(termsOut.getFilePointer());
        int len = term.length();
        termsOut.writeVInt(len);
        termsOut.writeBytes(term.bytes(), term.offset(), len);
        return nextTermId++;
    }

    @Override
    public void accept(int termId, int docId, int[] positions, int positionCount) throws IOException {
        if (positionCount <= 0 || docId < 0 || docId >= maxDoc) return;
        byte[] rec = recordScratch;
        VH_BE_INT.set(rec, 0, docId);
        VH_BE_INT.set(rec, 8, termId);
        BytesRef br = new BytesRef(rec, 0, RECORD_BYTES);
        for (int i = 0; i < positionCount; i++) {
            VH_BE_INT.set(rec, 4, positions[i]);
            sortInputWriter.write(br);
        }
    }

    public SidecarReader buildAndOpenReader() throws IOException {
        if (built) throw new IllegalStateException("buildAndOpenReader already called");
        built = true;

        termsOut.close();
        termOffsetsOut.close();
        CodecUtil.writeFooter(sortInputOut); // OfflineSorter requires a codec footer on its input.
        sortInputWriter.close();

        OfflineSorter sorter = new OfflineSorter(
                dir, "sort", Comparator.naturalOrder(), sortBufferSize,
                OfflineSorter.MAX_TEMPFILES, RECORD_BYTES, null, 0);
        String sortedName = sorter.sort(SORT_INPUT_FILE);
        try {
            streamSortedPairsToSidecar(sortedName);
        } finally {
            dir.deleteFile(SORT_INPUT_FILE);
            dir.deleteFile(sortedName);
        }

        closed = true;
        return SidecarReader.open(spillDir, maxDoc, nextTermId);
    }

    private void streamSortedPairsToSidecar(String sortedName) throws IOException {
        try (IndexOutput sideOut     = dir.createOutput(SIDECAR_FILE, IOContext.DEFAULT);
             IndexOutput docIndexOut = dir.createOutput(DOC_INDEX_FILE, IOContext.DEFAULT);
             OfflineSorter.ByteSequencesReader reader = new OfflineSorter.ByteSequencesReader(
                     dir.openChecksumInput(sortedName), sortedName)) {

            DocPayloadEmitter emitter = new DocPayloadEmitter(sideOut, docIndexOut);
            BytesRef rec;
            while ((rec = reader.next()) != null) {
                if (rec.length != RECORD_BYTES) throw new IOException("bad record length " + rec.length);
                int docId  = (int) VH_BE_INT.get(rec.bytes, rec.offset);
                int pos    = (int) VH_BE_INT.get(rec.bytes, rec.offset + 4);
                int termId = (int) VH_BE_INT.get(rec.bytes, rec.offset + 8);
                emitter.accept(docId, pos, termId);
            }
            emitter.finish(maxDoc);
        }
    }

    /** Emits one doc's delta-pos/termId pairs, padding sentinel offsets for docs with no tokens. */
    private static final class DocPayloadEmitter {
        private final IndexOutput sideOut;
        private final IndexOutput docIndexOut;
        private final ByteBuffersDataOutput staging = ByteBuffersDataOutput.newResettableInstance();
        private int currentDoc = -1;
        private int pairCount = 0;
        private int prevPos = 0;
        private int nextDoc = 0;

        DocPayloadEmitter(IndexOutput sideOut, IndexOutput docIndexOut) {
            this.sideOut = sideOut;
            this.docIndexOut = docIndexOut;
        }

        void accept(int docId, int pos, int termId) throws IOException {
            if (docId != currentDoc) {
                if (currentDoc >= 0) flushDoc();
                currentDoc = docId;
                pairCount = 0;
                prevPos = 0;
                staging.reset();
            }
            staging.writeVInt(pos - prevPos);
            staging.writeVInt(termId);
            prevPos = pos;
            pairCount++;
        }

        void finish(int maxDoc) throws IOException {
            if (currentDoc >= 0) flushDoc();
            padDocIndexTo(maxDoc);
        }

        private void flushDoc() throws IOException {
            padDocIndexTo(currentDoc);
            if (currentDoc < nextDoc) {
                throw new IllegalStateException("doc-index out of order: " + currentDoc + " < " + nextDoc);
            }
            docIndexOut.writeLong(sideOut.getFilePointer());
            nextDoc = currentDoc + 1;
            sideOut.writeVInt(pairCount);
            staging.copyTo(sideOut);
        }

        private void padDocIndexTo(int upTo) throws IOException {
            while (nextDoc < upTo) {
                docIndexOut.writeLong(DOC_INDEX_NO_TOKENS);
                nextDoc++;
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        IOUtils.closeWhileHandlingException(termsOut, termOffsetsOut, sortInputWriter, dir);
        try {
            Files.deleteIfExists(spillDir.resolve(SORT_INPUT_FILE));
        } catch (IOException e) {
            log.debug("Ignored sort-input delete error: {}", e.toString());
        }
    }
}
