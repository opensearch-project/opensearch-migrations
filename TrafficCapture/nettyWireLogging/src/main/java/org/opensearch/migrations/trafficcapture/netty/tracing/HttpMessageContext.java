package org.opensearch.migrations.trafficcapture.netty.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.commoncontexts.IRequestContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.time.Instant;

public class HttpMessageContext implements IRequestContext, IWithStartTimeAndAttributes<IConnectionContext> {
    public enum HttpTransactionState {
        REQUEST,
        INTERNALLY_BLOCKED,
        WAITING,
        RESPONSE
    }

    @Getter
    final long sourceRequestIndex;
    @Getter
    final ConnectionContext enclosingScope;
    @Getter
    final Instant startTime;
    @Getter
    final HttpTransactionState state;
    @Getter
    final Span currentSpan;

    public HttpMessageContext(ConnectionContext enclosingScope, long sourceRequestIndex, HttpTransactionState state,
                              ISpanWithParentGenerator spanGenerator) {
        this.sourceRequestIndex = sourceRequestIndex;
        this.enclosingScope = enclosingScope;
        this.startTime = Instant.now();
        this.state = state;
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes(), enclosingScope.getCurrentSpan());
    }
}
