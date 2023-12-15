package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import lombok.NonNull;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.IWithAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

import java.time.Instant;

public abstract class AbstractNestedSpanContext<T extends IWithAttributes> implements
        IWithAttributes, IWithStartTime {
    final T enclosingScope;
    @Getter final Instant startTime;
    @Getter private Span currentSpan;

    public AbstractNestedSpanContext(@NonNull T enclosingScope) {
        this.enclosingScope = enclosingScope;
        this.startTime = Instant.now();
    }

    @Override
    public IWithAttributes getEnclosingScope() {
        return enclosingScope;
    }

    public T getImmediateEnclosingScope() { return enclosingScope; }

    protected void setCurrentSpan(@NonNull ISpanWithParentGenerator spanGenerator) {
        setCurrentSpan(spanGenerator.apply(getPopulatedAttributes(), enclosingScope.getCurrentSpan()));
    }

    protected void setCurrentSpan(@NonNull Span s) {
        assert currentSpan == null : "only expect to set the current span once";
        currentSpan = s;
    }
}
