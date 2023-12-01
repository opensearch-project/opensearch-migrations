package org.opensearch.migrations.trafficcapture.netty.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.IWithStartTimeAndAttributes;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.commoncontexts.IRequestContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import java.time.Instant;

public class HttpMessageContext implements IRequestContext, IWithStartTimeAndAttributes<IConnectionContext> {

    public enum Direction {
        REQUEST,
        RESPONSE
    }

    @Getter
    final long sourceRequestIndex;
    @Getter
    final ConnectionContext enclosingScope;
    @Getter
    final Instant startTime;
    @Getter
    final Direction direction;
    @Getter
    final Span currentSpan;

    public HttpMessageContext(ConnectionContext enclosingScope, long sourceRequestIndex, Direction direction,
                              ISpanWithParentGenerator spanGenerator) {
        this.sourceRequestIndex = sourceRequestIndex;
        this.enclosingScope = enclosingScope;
        this.startTime = Instant.now();
        this.direction = direction;
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes(), enclosingScope.getCurrentSpan());
    }
}
