package org.opensearch.migrations.replay.tracing;

import lombok.NonNull;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

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
