package org.opensearch.migrations.tracing;

public abstract class BaseNestedSpanContext<
    S extends IInstrumentConstructor,
    T extends IScopedInstrumentationAttributes> extends BaseSpanContext<S> {
    final T enclosingScope;

    protected BaseNestedSpanContext(S rootScope, T enclosingScope) {
        super(rootScope);
        this.enclosingScope = enclosingScope;
    }

    protected void initializeSpan() {
        initializeSpan(rootInstrumentationScope);
    }

    @Override
    public IScopedInstrumentationAttributes getEnclosingScope() {
        return enclosingScope;
    }

    public T getImmediateEnclosingScope() {
        return enclosingScope;
    }

}
