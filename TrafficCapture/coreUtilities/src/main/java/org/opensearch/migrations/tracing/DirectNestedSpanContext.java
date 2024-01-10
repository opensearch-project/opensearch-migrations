package org.opensearch.migrations.tracing;

public abstract class DirectNestedSpanContext<S extends IInstrumentConstructor, T extends IInstrumentationAttributes<S>>
        extends AbstractNestedSpanContext<S, T>
        implements IWithTypedEnclosingScope<S, T> {
    public DirectNestedSpanContext(T enclosingScope) {
        super(enclosingScope);
    }

    @Override
    public T getLogicalEnclosingScope() {
        return (T) getEnclosingScope();
    }
}
