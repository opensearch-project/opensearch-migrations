package org.opensearch.migrations.bulkload.pipeline.spi;

/**
 * Where a source reads from, for failure messages. Any field may be null.
 *
 * @param name   the snapshot, backup or session being read
 * @param uri    the repository or stream it lives in
 * @param region the storage region, when there is one
 */
public record SourceLocation(String name, String uri, String region) {}
