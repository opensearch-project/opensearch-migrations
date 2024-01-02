package org.opensearch.migrations.tracing;

public interface IWithTypedEnclosingScope<T> extends IInstrumentationAttributes {
    T getLogicalEnclosingScope();
}
