package org.opensearch.migrations.snapshot.creation.tracing;

import io.opentelemetry.api.metrics.Meter;

import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.RfsContexts;
import lombok.NonNull;

public class CreateSnapshotContext extends BaseSpanContext<RootSnapshotContext>
    implements
        IRfsContexts.ICreateSnapshotContext {

    protected CreateSnapshotContext(RootSnapshotContext rootScope) {
        super(rootScope);
        initializeSpan(rootScope);
    }

    @Override
    public String getActivityName() {
        return ACTIVITY_NAME;
    }

    @Override
    public IScopedInstrumentationAttributes getEnclosingScope() {
        return null;
    }

    @Override
    public IRfsContexts.IRequestContext createRegisterRequest() {
        return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this, "createRegisterRequest");
    }

    @Override
    public IRfsContexts.IRequestContext createSnapshotContext() {
        return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this, "createSnapshotContext");
    }

    @Override
    public IRfsContexts.IRequestContext createGetSnapshotContext() {
        return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this, "createGetSnapshotContext");
    }

    public static class MetricInstruments extends CommonScopedMetricInstruments {
        private MetricInstruments(Meter meter, String activityName) {
            super(meter, activityName);
        }
    }

    public static @NonNull MetricInstruments makeMetrics(Meter meter) {
        return new MetricInstruments(meter, ACTIVITY_NAME);
    }

    @Override
    public MetricInstruments getMetrics() {
        return getRootInstrumentationScope().snapshotInstruments;
    }

}
