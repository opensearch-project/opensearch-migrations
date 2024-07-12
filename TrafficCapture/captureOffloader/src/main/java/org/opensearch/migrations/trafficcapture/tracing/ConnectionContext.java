package org.opensearch.migrations.trafficcapture.tracing;

import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;

import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IHasRootInstrumentationScope;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import lombok.Getter;
import lombok.NonNull;

public class ConnectionContext extends BaseSpanContext<IRootOffloaderContext>
    implements
        IConnectionContext,
        IHasRootInstrumentationScope<IRootOffloaderContext> {

    public static final String ACTIVE_CONNECTION = "activeConnection";
    public static final String ACTIVITY_NAME = "captureConnection";

    @Getter
    public final String connectionId;
    @Getter
    public final String nodeId;

    @Override
    public String getActivityName() {
        return ACTIVITY_NAME;
    }

    public ConnectionContext(IRootOffloaderContext rootInstrumentationScope, String connectionId, String nodeId) {
        super(rootInstrumentationScope);
        this.connectionId = connectionId;
        this.nodeId = nodeId;
        initializeSpan(rootInstrumentationScope);
        meterDeltaEvent(getMetrics().activeConnectionsCounter, 1);
    }

    public static class MetricInstruments extends CommonScopedMetricInstruments {
        private final LongUpDownCounter activeConnectionsCounter;

        protected MetricInstruments(Meter meter, String activityName) {
            super(meter, activityName);
            activeConnectionsCounter = meter.upDownCounterBuilder(ConnectionContext.ACTIVE_CONNECTION)
                .setUnit("count")
                .build();
        }
    }

    public static @NonNull MetricInstruments makeMetrics(Meter meter) {
        return new MetricInstruments(meter, ACTIVITY_NAME);
    }

    public @NonNull MetricInstruments getMetrics() {
        return getRootInstrumentationScope().getConnectionInstruments();
    }

    @Override
    public void sendMeterEventsForEnd() {
        super.sendMeterEventsForEnd();
        meterDeltaEvent(getMetrics().activeConnectionsCounter, -1);
    }
}
