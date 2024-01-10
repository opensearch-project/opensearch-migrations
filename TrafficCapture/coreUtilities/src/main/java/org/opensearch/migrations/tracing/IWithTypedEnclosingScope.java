package org.opensearch.migrations.tracing;

public interface IWithTypedEnclosingScope<S extends IInstrumentConstructor,T> extends IInstrumentationAttributes<S> {
    T getLogicalEnclosingScope();
}
