package org.opensearch.migrations.tracing;

public abstract class DirectNestedSpanContext<
    S extends IInstrumentConstructor,
    T extends IScopedInstrumentationAttributes & IHasRootInstrumentationScope<S>,
    L> extends BaseNestedSpanContext<S, T> implements IWithTypedEnclosingScope<L> {
    protected DirectNestedSpanContext(T parent) {
        super(parent.getRootInstrumentationScope(), parent);
    }

    @Override
    public L getLogicalEnclosingScope() {
        return (L) getEnclosingScope();
    }
}
