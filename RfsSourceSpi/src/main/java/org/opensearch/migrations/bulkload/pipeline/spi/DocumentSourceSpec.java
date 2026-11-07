package org.opensearch.migrations.bulkload.pipeline.spi;

import java.util.List;

/**
 * The parsed configuration for one document source.
 *
 * <p>Each provider defines its own spec type, because the things sources are configured with are not
 * one concept. Only what the caller needs from every source is shared here.
 */
public interface DocumentSourceSpec {

    /**
     * The provider this spec belongs to. Must equal the {@code kind()} of the provider that parsed
     * it, after normalization.
     */
    String kind();

    /** The collections to migrate; empty means all. Shard preparation filters on these. */
    default List<String> indexAllowlist() {
        return List.of();
    }

    /** Where this source reads from, for failure messages. */
    default SourceLocation location() {
        return new SourceLocation(null, null, null);
    }
}
