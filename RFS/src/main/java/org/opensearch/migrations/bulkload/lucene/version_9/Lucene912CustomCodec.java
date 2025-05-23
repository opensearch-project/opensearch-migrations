package org.opensearch.migrations.bulkload.lucene.version_9;

import java.util.Set;

import shadow.lucene9.org.apache.lucene.codecs.Codec;
import shadow.lucene9.org.apache.lucene.codecs.FilterCodec;
import shadow.lucene9.org.apache.lucene.codecs.StoredFieldsFormat;

/**
 *
 * Extends {@link FilterCodec} to reuse the functionality of Lucene Codec.
 * Supports two modes zstd and zstd_no_dict.
 * Uses Lucene912 as the delegate codec
 *
 */
public abstract class Lucene912CustomCodec extends FilterCodec {

    public static final int DEFAULT_COMPRESSION_LEVEL = 3;
    /** Each mode represents a compression algorithm. */
    public enum Mode {
        /**
         * ZStandard mode with dictionary
         */
        ZSTD("ZSTD912", Set.of("zstd")),
        /**
         * ZStandard mode without dictionary
         */
        ZSTD_NO_DICT("ZSTDNODICT912", Set.of("zstd_no_dict"));

        private final String codec;
        private final Set<String> aliases;

        Mode(String codec, Set<String> aliases) {
            this.codec = codec;
            this.aliases = aliases;
        }

        /**
         * Returns the Codec that is registered with Lucene
         */
        public String getCodec() {
            return codec;
        }

        /**
         * Returns the aliases of the Codec
         */
        public Set<String> getAliases() {
            return aliases;
        }
    }

    private final StoredFieldsFormat storedFieldsFormat;

    /**
     * Creates a new compression codec with the default compression level.
     *
     * @param mode The compression codec (ZSTD or ZSTDNODICT).
     */
    public Lucene912CustomCodec(Mode mode) {
        this(mode, DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * Creates a new compression codec with the given compression level. We use
     * lowercase letters when registering the codec so that we remain consistent with
     * the other compression codecs: default, lucene_default, and best_compression.
     *
     * @param mode The compression codec (ZSTD or ZSTDNODICT).
     * @param compressionLevel The compression level.
     */
    public Lucene912CustomCodec(Mode mode, int compressionLevel) {
        super(mode.getCodec(), Codec.forName("Lucene912"));
        this.storedFieldsFormat = new ZstdStoredFields814Format(mode, compressionLevel);
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return storedFieldsFormat;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
