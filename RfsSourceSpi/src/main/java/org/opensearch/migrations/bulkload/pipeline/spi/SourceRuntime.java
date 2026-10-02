package org.opensearch.migrations.bulkload.pipeline.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

/**
 * Framework services a provider is handed at construction time.
 *
 * <p>User configuration lives in the provider's spec, not here. It stops at
 * {@code IDeltaStreamContext} on purpose: the SPI must not grow a shadow of the RFS tracing
 * hierarchy.
 *
 * @param scratchDir                 directory for downloaded/derived files that can be re-fetched
 * @param workDir                    directory for unpacked working data (e.g. Lucene segments)
 * @param deltaStreamContextFactory  supplies a tracing context per delta stream; never null,
 *                                   defaults to a no-op when the caller has no tracing
 */
public record SourceRuntime(
    Path scratchDir,
    Path workDir,
    Supplier<IRfsContexts.IDeltaStreamContext> deltaStreamContextFactory
) {
    public SourceRuntime {
        Objects.requireNonNull(scratchDir, "scratchDir must not be null");
        Objects.requireNonNull(workDir, "workDir must not be null");
        Objects.requireNonNull(deltaStreamContextFactory, "deltaStreamContextFactory must not be null");
    }
}
