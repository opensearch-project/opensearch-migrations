package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.Getter;
import lombok.NonNull;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.AttributeNameMatchingPredicate;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.FilteringAttributeBuilder;
import org.opensearch.migrations.tracing.IHasRootInstrumentationScope;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

public class ConnectionContext extends BaseNestedSpanContext<RootOffloaderContext, RootOffloaderContext>
        implements IConnectionContext, IHasRootInstrumentationScope<RootOffloaderContext> {

    private static final AttributeNameMatchingPredicate KEYS_TO_EXCLUDE_FOR_ACTIVE_CONNECTION_COUNT =
            AttributeNameMatchingPredicate.builder(true).add(CONNECTION_ID_ATTR.getKey()).build();
    public static final String ACTIVE_CONNECTION = "activeConnection";
    public static final String ACTIVITY_NAME = "captureConnection";

    @Getter
    public final String connectionId;
    @Getter
    public final String nodeId;

    @Override
    public String getActivityName() { return ACTIVITY_NAME; }

    public ConnectionContext(RootOffloaderContext rootInstrumentationScope, String connectionId, String nodeId) {
        super(rootInstrumentationScope, rootInstrumentationScope);
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        initializeSpan();
        meterDeltaEvent(getMetrics().activeConnectionsCounter, 1);
    }

    public static class MetricInstruments extends CommonScopedMetricInstruments {
        private final LongUpDownCounter activeConnectionsCounter;

        public MetricInstruments(Meter meter, String scopeName) {
            super(meter, ACTIVITY_NAME);
            activeConnectionsCounter = meter.upDownCounterBuilder(ConnectionContext.ACTIVE_CONNECTION)
                    .setUnit("count").build();
        }
    }

    public @NonNull MetricInstruments getMetrics() {
        return getRootInstrumentationScope().connectionInstruments;
    }

    @Override
    public void sendMeterEventsForEnd() {
        meterDeltaEvent(getMetrics().activeConnectionsCounter, 1);
    }

    @Override
    public void onConnectionCreated() {
        meterDeltaEvent(getMetrics().activeConnectionsCounter, 1);
    }
    @Override
    public void onConnectionClosed() {
        meterDeltaEvent(getMetrics().activeConnectionsCounter, -1);
    }
}
