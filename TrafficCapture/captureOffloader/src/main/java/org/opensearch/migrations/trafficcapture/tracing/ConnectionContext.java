package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.common.Attributes;
import lombok.Getter;
import org.opensearch.migrations.tracing.AbstractNestedSpanContext;
import org.opensearch.migrations.tracing.FilteringAttributeBuilder;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import java.util.Set;

public class ConnectionContext extends AbstractNestedSpanContext<RootOtelContext> implements IConnectionContext {
    private static final Set KEYS_TO_ALLOW_FOR_ACTIVE_CONNECTION_COUNT = Set.of(CONNECTION_ID_ATTR.getKey());
    public static final String ACTIVE_CONNECTION = "activeConnection";

    @Getter
    public final String connectionId;
    @Getter
    public final String nodeId;

    @Override
    public String getActivityName() { return "captureConnection"; }

    public ConnectionContext(RootOtelContext rootInstrumentationScope,
                             String connectionId, String nodeId) {
        super(rootInstrumentationScope);
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        initializeSpan();
        meterDeltaEvent(ACTIVE_CONNECTION, 1,
                new FilteringAttributeBuilder(Attributes.builder(), false, KEYS_TO_ALLOW_FOR_ACTIVE_CONNECTION_COUNT));
    }

    @Override
    public void sendMeterEventsForEnd() {
        super.sendMeterEventsForEnd();
        meterDeltaEvent(ACTIVE_CONNECTION, -1,
                new FilteringAttributeBuilder(Attributes.builder(), false, KEYS_TO_ALLOW_FOR_ACTIVE_CONNECTION_COUNT));
    }
}
