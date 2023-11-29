package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
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

    public ConnectionContext(ConnectionContext oldContext, Span currentSpan) {
        this.connectionId = oldContext.getConnectionId();
        this.nodeId = oldContext.getNodeId();
        this.currentSpan = currentSpan;
        this.startTime = Instant.now();
    }

    public ConnectionContext(String connectionId, String nodeId, Span currentSpan) {
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        this.currentSpan = currentSpan;
        this.startTime = Instant.now();
    }
}
