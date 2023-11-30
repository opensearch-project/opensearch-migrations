package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.coreutils.SpanGenerator;
import org.opensearch.migrations.coreutils.SpanWithParentGenerator;
import org.opensearch.migrations.tracing.IConnectionContext;
import org.opensearch.migrations.tracing.IWithStartTime;

import java.time.Instant;

public class ConnectionContext implements IConnectionContext, IWithStartTime {
    @Getter
    public final String connectionId;
    @Getter
    public final String nodeId;
    @Getter
    public final Span currentSpan;
    @Getter
    private final Instant startTime;

    public ConnectionContext(ConnectionContext oldContext, SpanWithParentGenerator spanGenerator) {
        this.connectionId = oldContext.getConnectionId();
        this.nodeId = oldContext.getNodeId();
        this.startTime = Instant.now();
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes(), oldContext.getCurrentSpan());
    }

    public ConnectionContext(String connectionId, String nodeId, SpanGenerator spanGenerator) {
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes());
        this.startTime = Instant.now();
    }
}
