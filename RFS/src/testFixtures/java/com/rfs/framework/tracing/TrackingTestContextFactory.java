package com.rfs.framework.tracing;

import lombok.Lombok;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class TrackingTestContextFactory<T> extends TrackingTestContextCreator {

    private final Class<T> c;

    public TrackingTestContextFactory(Class<T> c) {
        this.c = c;
    }

    T buildRootContext(InMemoryInstrumentationBundle a, IContextTracker b) {
        try {
            return c.getDeclaredConstructor(InMemoryInstrumentationBundle.class, IContextTracker.class)
                    .newInstance(a,b);
        } catch (Exception e) {
            throw Lombok.sneakyThrow(e);
        }
    }

    public T withTracking(boolean tracing, boolean metrics, boolean consoleLogging) {
        return withTracking(tracing, metrics, consoleLogging, this::buildRootContext);
    }

    public T withAllTracking() {
        return withAllTracking(this::buildRootContext);
    }

    public T noOtelTracking() {
        return noOtelTracking(this::buildRootContext);
    }
}
