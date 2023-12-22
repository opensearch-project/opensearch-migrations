package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.ISpanGenerator;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
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
    @Getter
    final IInstrumentConstructor rootInstrumentationScope;

    public ConnectionContext(IInstrumentConstructor rootInstrumentationScope,
                             String connectionId, String nodeId) {
        this.rootInstrumentationScope = rootInstrumentationScope;
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        this.currentSpan = rootInstrumentationScope.buildSpanWithoutParent("","connectionLifetime");
        this.startTime = Instant.now();
    }
}
