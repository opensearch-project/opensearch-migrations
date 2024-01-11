package org.opensearch.migrations.tracing;

import lombok.NonNull;

public abstract class IndirectNestedSpanContext<S extends IInstrumentConstructor,
                                                D extends IInstrumentationAttributes & IHasRootInstrumentationScope<S>,
                                                L>
        extends BaseNestedSpanContext<S, D>
        implements IWithTypedEnclosingScope<L>
{
    protected IndirectNestedSpanContext(@NonNull D enclosingScope) {
        super(enclosingScope.getRootInstrumentationScope(), enclosingScope);
    }

    public abstract L getLogicalEnclosingScope();
}
