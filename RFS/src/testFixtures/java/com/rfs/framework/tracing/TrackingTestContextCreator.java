package com.rfs.framework.tracing;

import org.opensearch.migrations.tracing.BacktracingContextTracker;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.LoggingContextTracer;

import java.util.function.BiFunction;

public class TrackingTestContextCreator {

    public static <T> T withTracking(boolean tracing, boolean metrics, boolean consoleLogging,
                                     BiFunction<InMemoryInstrumentationBundle, IContextTracker, T> ctor) {
        IContextTracker tracker = new BacktracingContextTracker();
        if (consoleLogging) {
            tracker = new CompositeContextTracker(tracker, new LoggingContextTracer());
        }

        return ctor.apply(new InMemoryInstrumentationBundle(tracing, metrics), tracker);
    }

    public static <T> T withAllTracking(BiFunction<InMemoryInstrumentationBundle, IContextTracker, T> ctor) {
        return withTracking(true, true, true, ctor);
    }

    public static <T> T noOtelTracking(BiFunction<InMemoryInstrumentationBundle, IContextTracker, T> ctor) {
        return withTracking(false, false, false, ctor);
    }
}
