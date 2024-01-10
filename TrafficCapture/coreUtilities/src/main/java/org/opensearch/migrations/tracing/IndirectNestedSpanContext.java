package org.opensearch.migrations.tracing;

import lombok.NonNull;

public abstract class IndirectNestedSpanContext
        <S extends IInstrumentConstructor, D extends IInstrumentationAttributes<S>, L extends IInstrumentationAttributes<S>>
        extends AbstractNestedSpanContext<S, D> {
    public IndirectNestedSpanContext(@NonNull D enclosingScope) {
        super(enclosingScope);
    }

    public abstract L getLogicalEnclosingScope();
}
