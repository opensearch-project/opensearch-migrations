package org.opensearch.migrations.tracing;

import lombok.Getter;

/**
 * This exists as helper glue to make pattern matching in the generics
 * work to allow for more simplified constructors.
 */
public interface IHasRootInstrumentationScope<S> {
    S getRootInstrumentationScope();
}
