package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.common.Attributes;
import lombok.Getter;
import org.opensearch.migrations.tracing.AbstractNestedSpanContext;
import org.opensearch.migrations.tracing.AttributeNameMatchingPredicate;
import org.opensearch.migrations.tracing.FilteringAttributeBuilder;
import org.opensearch.migrations.tracing.IRootOtelContext;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

public class ConnectionContext extends AbstractNestedSpanContext<IRootOffloaderContext> implements IConnectionContext {
    private static final AttributeNameMatchingPredicate KEYS_TO_EXCLUDE_FOR_ACTIVE_CONNECTION_COUNT =
            AttributeNameMatchingPredicate.builder(true).add(CONNECTION_ID_ATTR.getKey()).build();
    public static final String ACTIVE_CONNECTION = "activeConnection";

    @Getter
    public final String connectionId;
    @Getter
    public final String nodeId;

    @Override
    public String getActivityName() { return "captureConnection"; }

    public ConnectionContext(IRootOffloaderContext rootInstrumentationScope, String connectionId, String nodeId) {
        super(rootInstrumentationScope);
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        initializeSpan();
        meterDeltaEvent(rootInstrumentationScope.getActiveConnectionsCounter(), 1,
                new FilteringAttributeBuilder(Attributes.builder(), KEYS_TO_EXCLUDE_FOR_ACTIVE_CONNECTION_COUNT));
    }

    @Override
    public void sendMeterEventsForEnd() {
        //super.sendMeterEventsForEnd();
//        meterIncrementEvent(getEndOfScopeMetricName());
//        meterHistogramMicros(getEndOfScopeDurationMetricName());

        meterDeltaEvent(getRootInstrumentationScope().getActiveConnectionsCounter(), 1,
                new FilteringAttributeBuilder(Attributes.builder(), KEYS_TO_EXCLUDE_FOR_ACTIVE_CONNECTION_COUNT));
    }
}
