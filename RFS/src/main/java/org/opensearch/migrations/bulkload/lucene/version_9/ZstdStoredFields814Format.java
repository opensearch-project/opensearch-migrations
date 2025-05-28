package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.util.Objects;

import org.opensearch.migrations.bulkload.lucene.version_9.Lucene912CustomCodec.Mode;

import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsReader;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsWriter;
import shadow.lucene9.org.apache.lucene.codecs.compressing.CompressionMode;
import shadow.lucene9.org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;
import shadow.lucene9.org.apache.lucene.index.FieldInfos;
import shadow.lucene9.org.apache.lucene.index.SegmentInfo;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.IOContext;

/** Stored field format used by pluggable codec */
public class ZstdStoredFields814Format extends StoredFieldsFormat {

    /** A key that we use to map to a mode */
    public static final String MODE_KEY = ZstdStoredFields814Format.class.getSimpleName() + ".mode";

    private static final String ZSTD_CODEC_HEADER = "ZstdStoredFields814";
    // Assuming same value for testing
    private static final String ZSTD_NO_DICT_CODEC_HEADER = "ZstdStoredFields814";

    protected static final int ZSTD_BLOCK_LENGTH = 10 * 48 * 1024;
    protected static final int ZSTD_MAX_DOCS_PER_BLOCK = 4096;
    protected static final int ZSTD_BLOCK_SHIFT = 10;

    private final CompressionMode zstdCompressionMode;
    private final CompressionMode zstdNoDictCompressionMode;

    private final Mode mode;
    private final int compressionLevel;

    /** default constructor */
    public ZstdStoredFields814Format() {
        this(Mode.ZSTD_NO_DICT, Lucene912CustomCodec.DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * Creates a new instance.
     *
     * @param mode The mode represents ZSTD or ZSTDNODICT
     */
    public ZstdStoredFields814Format(Mode mode) {
        this(mode, Lucene912CustomCodec.DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * Creates a new instance with the specified mode and compression level.
     *
     * @param mode The mode represents ZSTD or ZSTDNODICT
     * @param compressionLevel The compression level for the mode.
     */
    public ZstdStoredFields814Format(Mode mode, int compressionLevel) {
        this.mode = Objects.requireNonNull(mode);
        this.compressionLevel = compressionLevel;
        zstdCompressionMode = new ZstdCompressionMode(compressionLevel) {
        };
        zstdNoDictCompressionMode = new ZstdNoDictCompressionMode(compressionLevel) {
        };
    }

    /**
     * Returns a {@link StoredFieldsReader} to load stored fields.
     * @param directory The index directory.
     * @param si The SegmentInfo that stores segment information.
     * @param fn The fieldInxfos.
     * @param context The IOContext that holds additional details on the merge/search context.
     */
    @Override
    public StoredFieldsReader fieldsReader(Directory directory, SegmentInfo si, FieldInfos fn, IOContext context) throws IOException {
        String value = si.getAttribute(MODE_KEY);
        Mode mode;
        if (value != null) {
            mode = Mode.valueOf(value);
        } else {
            System.out.println(">>>>> WARNING: No MODE_KEY in SegmentInfo " + si.name + ", falling back to Mode.ZSTD_NO_DICT");
            mode = Mode.ZSTD_NO_DICT;
        }
        return impl(mode).fieldsReader(directory, si, fn, context);
    }

    /**
     * Returns a {@link StoredFieldsReader} to write stored fields.
     * @param directory The index directory.
     * @param si The SegmentInfo that stores segment information.
     * @param context The IOContext that holds additional details on the merge/search context.
     */
    @Override
    public StoredFieldsWriter fieldsWriter(Directory directory, SegmentInfo si, IOContext context) throws IOException {
        String previous = si.putAttribute(MODE_KEY, mode.name());
        if (previous != null && !previous.equals(mode.name())) {
            throw new IllegalStateException(
                    "found existing value for " + MODE_KEY + " for segment: " + si.name + " old = " + previous + ", new = " + mode.name()
            );
        }
        return impl(mode).fieldsWriter(directory, si, context);
    }

    StoredFieldsFormat impl(Mode mode) {
        switch (mode) {
            case ZSTD:
                return getCustomCompressingStoredFieldsFormat(ZSTD_CODEC_HEADER, this.zstdCompressionMode);
            case ZSTD_NO_DICT:
                return getCustomCompressingStoredFieldsFormat(ZSTD_NO_DICT_CODEC_HEADER, this.zstdNoDictCompressionMode);
            default:
                throw new IllegalStateException("Unsupported compression mode: " + mode);
        }
    }

    private StoredFieldsFormat getCustomCompressingStoredFieldsFormat(String formatName, CompressionMode compressionMode) {
        return new Lucene90CompressingStoredFieldsFormat(
                formatName,
                compressionMode,
                ZSTD_BLOCK_LENGTH,
                ZSTD_MAX_DOCS_PER_BLOCK,
                ZSTD_BLOCK_SHIFT
        );
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Returns the compression level.
     */
    public int getCompressionLevel() {
        return compressionLevel;
    }

    public CompressionMode getCompressionMode() {
        return mode == Mode.ZSTD_NO_DICT ? zstdNoDictCompressionMode : zstdCompressionMode;
    }

}
