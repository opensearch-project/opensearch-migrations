package com.rfs.tracing;

import io.opentelemetry.api.OpenTelemetry;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

public class RootRfsContext extends RootOtelContext {
    public static final String SCOPE_NAME = "rfs";
    public final RfsContexts.CreateSnapshotContext.MetricInstruments snapshotInstruments;
    public final RfsContexts.ClusterMetadataContext.MetricInstruments metadataMetrics;
    public final RfsContexts.MigrateTemplateContext.MetricInstruments indexTemplateInstruments;
    public final RfsContexts.GenericRequestContext.MetricInstruments genericRequestInstruments;
    public final RfsContexts.CheckedIdempotentPutRequestContext.MetricInstruments getTwoStepIdempotentRequestInstruments;
    public final RfsContexts.CreateIndexContext.MetricInstruments createIndexInstruments;
    public final RfsContexts.DocumentReindexContext.MetricInstruments documentReindexInstruments;
    public final RfsContexts.WorkingStateContext.MetricInstruments workingStateUpdateInstruments;

    public RootRfsContext(OpenTelemetry sdk, IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        snapshotInstruments = RfsContexts.CreateSnapshotContext.makeMetrics(meter);
        metadataMetrics = RfsContexts.ClusterMetadataContext.makeMetrics(meter);
        indexTemplateInstruments = RfsContexts.MigrateTemplateContext.makeMetrics(meter);
        genericRequestInstruments = RfsContexts.GenericRequestContext.makeMetrics(meter);
        getTwoStepIdempotentRequestInstruments = RfsContexts.CheckedIdempotentPutRequestContext.makeMetrics(meter);
        createIndexInstruments = RfsContexts.CreateIndexContext.makeMetrics(meter);
        documentReindexInstruments = RfsContexts.DocumentReindexContext.makeMetrics(meter);
        workingStateUpdateInstruments = RfsContexts.WorkingStateContext.makeMetrics(meter);
    }

    public IRfsContexts.ICreateSnapshotContext createSnapshotCreateContext() {
        return new RfsContexts.CreateSnapshotContext(this);
    }

    public IRfsContexts.IClusterMetadataContext createMetadataMigrationContext() {
        return new RfsContexts.ClusterMetadataContext(this);
    }

    public IRfsContexts.ICreateIndexContext createIndexContext() {
        return new RfsContexts.CreateIndexContext(this);
    }

    public IRfsContexts.IDocumentReindexContext createReindexContext() {
        return new RfsContexts.DocumentReindexContext(this);
    }

    public IRfsContexts.IWorkingStateContext createWorkingStateContext() {
        return new RfsContexts.WorkingStateContext(this);
    }
}
