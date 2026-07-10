package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import shadow.lucene9.org.apache.lucene.backward_codecs.lucene99.Lucene99PostingsFormat;
import shadow.lucene9.org.apache.lucene.codecs.FieldsConsumer;
import shadow.lucene9.org.apache.lucene.codecs.FieldsProducer;
import shadow.lucene9.org.apache.lucene.codecs.PostingsFormat;
import shadow.lucene9.org.apache.lucene.index.SegmentReadState;
import shadow.lucene9.org.apache.lucene.index.SegmentWriteState;
import shadow.lucene9.org.apache.lucene.store.BufferedChecksumIndexInput;
import shadow.lucene9.org.apache.lucene.store.ChecksumIndexInput;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.FilterDirectory;
import shadow.lucene9.org.apache.lucene.store.FilterIndexInput;
import shadow.lucene9.org.apache.lucene.store.IOContext;
import shadow.lucene9.org.apache.lucene.store.IndexInput;

/**
 * PostingsFormat SPI adapter for the {@code ES812Postings} format used by Elasticsearch 8.12+.
 *
 * <p>ES 8.12+ stores per-field postings with a custom codec whose files are named with the
 * full per-field suffix (e.g. {@code _0_ES812Postings_0.tim}) and whose file headers embed
 * ES-specific codec names ({@code ES812PostingsWriterDoc/Pos/Pay/Terms}) instead of the stock
 * Lucene names {@link Lucene99PostingsFormat} expects. The binary layout is otherwise
 * identical to {@link Lucene99PostingsFormat}, so we wrap the directory with a thin filter
 * that rewrites those codec name strings on read and delegate to the stock reader.
 *
 * <p>The substitution is applied in {@code DataInput.readString()} only — raw bytes are
 * never modified, so {@code BufferedChecksumIndexInput}'s footer CRC equality holds without
 * any custom checksum input. Only this version_9 path needs the rewrite: ES 5/6/7 predate
 * ES812, and the version_10 sibling returns {@link FallbackLuceneComponents#EMPTY_FIELDS_PRODUCER}
 * because Lucene 10 / ES 9.x reconstruction never decodes postings (stored fields suffice).
 *
 * <p>One wrinkle: {@code Directory.openChecksumInput} wraps our {@link RewritingInput} in a
 * {@link BufferedChecksumIndexInput} whose inherited {@link DataInput#readString()} reads
 * bytes directly via its own {@code readByte()}/{@code readBytes()}, bypassing the delegate's
 * {@code readString()} override. So we also override {@code openChecksumInput} and subclass
 * {@code BufferedChecksumIndexInput} to substitute codec names after {@code super.readString()}
 * (which still hashes the raw ES812 bytes, keeping the footer CRC intact).
 */
@Slf4j
public class Es812PostingsFormat extends PostingsFormat {

    static final String FORMAT_NAME = "ES812Postings";

    /**
     * Maps ES812 codec name strings to the Lucene names that {@code Lucene99PostingsReader}
     * (Doc/Pos/Pay) and {@code Lucene40BlockTreeTermsReader} (Terms) check at header time.
     */
    private static final Map<String, String> CODEC_RENAMES = Map.of(
        "ES812PostingsWriterDoc",   "Lucene99PostingsWriterDoc",
        "ES812PostingsWriterPos",   "Lucene99PostingsWriterPos",
        "ES812PostingsWriterPay",   "Lucene99PostingsWriterPay",
        "ES812PostingsWriterTerms", "Lucene90PostingsWriterTerms"
    );

    public Es812PostingsFormat() {
        super(FORMAT_NAME);
    }

    @Override
    public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
        throw new UnsupportedOperationException("ES812Postings is read-only fallback");
    }

    @Override
    public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
        // PerFieldPostingsFormat sets state.segmentSuffix = "ES812Postings_<n>" and
        // state.segmentInfo.name = "_0" — IndexFileNames.segmentFileName("_0", "ES812Postings_0", "tim")
        // resolves to "_0_ES812Postings_0.tim", which is what ES wrote. We only need to substitute
        // the codec name strings inside those files for the stock reader to accept them.
        SegmentReadState reScoped = new SegmentReadState(
            new RewritingDirectory(state.directory),
            state.segmentInfo, state.fieldInfos, state.context, state.segmentSuffix);

        try {
            return new Lucene99PostingsFormat().fieldsProducer(reScoped);
        } catch (IOException e) {
            log.warn("ES812Postings: Lucene99PostingsFormat fallback failed for segment {} suffix {}, returning empty. Cause: {}",
                state.segmentInfo.name, state.segmentSuffix, e.getMessage());
            return FallbackLuceneComponents.EMPTY_FIELDS_PRODUCER;
        }
    }

    /** Wraps every opened input with a {@link RewritingInput}; everything else delegates. */
    private static final class RewritingDirectory extends FilterDirectory {
        RewritingDirectory(Directory in) {
            super(in);
        }

        @Override
        public IndexInput openInput(String name, IOContext context) throws IOException {
            return new RewritingInput(super.openInput(name, context));
        }

        @Override
        public ChecksumIndexInput openChecksumInput(String name, IOContext context) throws IOException {
            // Default Directory.openChecksumInput wraps openInput() in a BufferedChecksumIndexInput
            // whose inherited readString() bypasses RewritingInput.readString(). Substitute on the
            // wrapper itself so the substitution still happens on CodecUtil.checkIndexHeader reads.
            return new RewritingChecksumIndexInput(super.openInput(name, context));
        }
    }

    /**
     * Substitutes ES812 codec names with their Lucene equivalents inside {@link #readString()}.
     * {@code readByte}/{@code readBytes} pass through untouched, so the wrapping
     * {@code BufferedChecksumIndexInput} CRCs the raw byte stream and footer equality holds
     * naturally — no custom checksum input needed.
     */
    private static final class RewritingInput extends FilterIndexInput {
        RewritingInput(IndexInput in) {
            super("Es812CodecRewriting(" + in + ")", in);
        }

        @Override
        public String readString() throws IOException {
            String raw = super.readString();
            return CODEC_RENAMES.getOrDefault(raw, raw);
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            return new RewritingInput(in.slice(sliceDescription, offset, length));
        }

        // FilterIndexInput.in is protected final, so super.clone()'s shallow copy would share
        // the delegate with the original — violating IndexInput's contract that clones have
        // independent position state. Copy-factory style (mirrors Lucene's own
        // EndiannessReverserIndexInput) clones the delegate explicitly. Sonar S1182 wants
        // super.clone() and S2975 wants no clone() override at all; both are unsound here
        // (the framework calls IndexInput.clone() polymorphically), so suppress both.
        @SuppressWarnings({"java:S1182", "java:S2975"})
        @Override
        public IndexInput clone() {
            return new RewritingInput(in.clone());
        }
    }

    /**
     * A {@link BufferedChecksumIndexInput} that substitutes ES812 codec names via
     * {@link #readString()}. Needed because the inherited {@code DataInput.readString()}
     * reads raw bytes (and hashes them into the running CRC), bypassing any
     * {@code readString()} override on the wrapped delegate. Overriding here lets the
     * super implementation read the raw ES812 bytes (keeping the footer CRC correct) while
     * we return the Lucene90/99 equivalent to satisfy {@code CodecUtil.checkIndexHeader}.
     */
    private static final class RewritingChecksumIndexInput extends BufferedChecksumIndexInput {
        RewritingChecksumIndexInput(IndexInput main) {
            super(main);
        }

        @Override
        public String readString() throws IOException {
            String raw = super.readString();
            return CODEC_RENAMES.getOrDefault(raw, raw);
        }
    }
}
