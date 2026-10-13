package org.opensearch.migrations.bulkload.pipeline.spi;

/**
 * Where a source's work coordination can live. Declared by a provider so the caller can validate
 * its own coordinator configuration without knowing which source it resolved.
 */
public enum CoordinationRequirement {

    /** Work may be coordinated on the target cluster; an external coordinator is optional. */
    TARGET_ALLOWED,

    /** Work cannot be coordinated on the target; the caller must supply an external coordinator. */
    EXTERNAL_REQUIRED
}
