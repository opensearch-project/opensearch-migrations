package org.opensearch.migrations.tracing;

public class DirectNestedSpanContext<T extends IInstrumentationAttributes>
        extends AbstractNestedSpanContext<T>
        implements IWithTypedEnclosingScope<T> {
    public DirectNestedSpanContext(T enclosingScope) {
        super(enclosingScope);
    }

    @Override
    public T getLogicalEnclosingScope() {
        return (T) getEnclosingScope();
    }
}
