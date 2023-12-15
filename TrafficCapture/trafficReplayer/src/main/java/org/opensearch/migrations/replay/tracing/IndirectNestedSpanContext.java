package org.opensearch.migrations.replay.tracing;

import lombok.NonNull;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public abstract class IndirectNestedSpanContext<D extends IScopedInstrumentationAttributes, L extends IScopedInstrumentationAttributes>
        extends AbstractNestedSpanContext<D> {
    public IndirectNestedSpanContext(@NonNull D enclosingScope) {
        super(enclosingScope);
    }

    public abstract L getLogicalEnclosingScope();
}
