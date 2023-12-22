package org.opensearch.migrations.tracing;

import lombok.NonNull;

public abstract class IndirectNestedSpanContext
        <D extends IInstrumentationAttributes, L extends IInstrumentationAttributes>
        extends AbstractNestedSpanContext<D> {
    public IndirectNestedSpanContext(@NonNull D enclosingScope) {
        super(enclosingScope);
    }

    public abstract L getLogicalEnclosingScope();
}
