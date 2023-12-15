package org.opensearch.migrations.tracing;

public interface IWithTypedEnclosingScope<T> {
    T getLogicalEnclosingScope();
}
