package org.opensearch.migrations.snapshot.creation.tracing;

import com.rfs.tracing.BaseRootRfsContext;
import com.rfs.tracing.IRfsContexts;
import io.opentelemetry.api.OpenTelemetry;
import org.opensearch.migrations.tracing.IContextTracker;

public class RootSnapshotContext extends BaseRootRfsContext implements IRootSnapshotContext {
    public static final String SCOPE_NAME = "createSnapshot";
    public final CreateSnapshotContext.MetricInstruments snapshotInstruments;

    public RootSnapshotContext(OpenTelemetry sdk, IContextTracker contextTracker) {
        super(sdk, contextTracker);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        snapshotInstruments = CreateSnapshotContext.makeMetrics(meter);
    }

    @Override
    public IRfsContexts.ICreateSnapshotContext createSnapshotCreateContext() {
        return new CreateSnapshotContext(this);
    }
}
