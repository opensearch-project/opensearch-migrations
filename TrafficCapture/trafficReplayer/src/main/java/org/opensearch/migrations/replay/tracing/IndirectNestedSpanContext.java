package org.opensearch.migrations.replay.tracing;

import lombok.NonNull;
import org.opensearch.migrations.tracing.IWithAttributes;

public abstract class IndirectNestedSpanContext<D extends IWithAttributes, L extends IWithAttributes>
        extends AbstractNestedSpanContext<D> {
    public IndirectNestedSpanContext(@NonNull D enclosingScope) {
        super(enclosingScope);
    }

    public abstract L getLogicalEnclosingScope();
}
