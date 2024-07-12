package org.opensearch.migrations.tracing;

/**
 * For debugging or observability purposes, this interface allows for tracking the
 * creation and termination of activities (such as those with spans).
 */
public interface IContextTracker {
    default void onContextCreated(IScopedInstrumentationAttributes newScopedContext) {}

    /**
     * This can be overridden to track creation and termination of spans
     */
    default void onContextClosed(IScopedInstrumentationAttributes newScopedContext) {}

    IContextTracker DO_NOTHING_TRACKER = new IContextTracker() {
    };
}
