package com.rfs.framework.tracing;

import java.util.function.BiFunction;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.SneakyThrows;

@AllArgsConstructor
public class TrackingTestContextFactory<T> extends TrackingTestContextCreator {

    BiFunction<InMemoryInstrumentationBundle, IContextTracker, T> factory;

    @SneakyThrows
    public static <U> TrackingTestContextFactory<U> factoryViaCtor(Class<U> c) {
        try {
            return new TrackingTestContextFactory<U>((a, b) -> {
                try {
                    return c.getDeclaredConstructor(InMemoryInstrumentationBundle.class, IContextTracker.class)
                        .newInstance(a, b);
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
        } catch (Exception e) {
            throw Lombok.sneakyThrow(e);
        }
    }

    public T withTracking(boolean tracing, boolean metrics, boolean consoleLogging) {
        return withTracking(tracing, metrics, consoleLogging, factory);
    }

    public T withAllTracking() {
        return withAllTracking(factory);
    }

    public T noOtelTracking() {
        return noOtelTracking(factory);
    }
}
