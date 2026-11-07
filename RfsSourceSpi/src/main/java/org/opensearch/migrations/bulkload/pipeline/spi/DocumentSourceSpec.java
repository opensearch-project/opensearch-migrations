package org.opensearch.migrations.bulkload.pipeline.spi;

/**
 * The parsed configuration for one document source.
 *
 * <p>Only {@link #kind()} is shared. Each provider defines its own spec type, because the things
 * sources are configured with are not one concept: an allowlist is an index list for ES, a key
 * prefix for raw S3 and a session id for the failure stream.
 */
public interface DocumentSourceSpec {

    /**
     * The provider this spec belongs to. Must equal the {@code kind()} of the provider that parsed
     * it, after normalization.
     */
    String kind();
}
