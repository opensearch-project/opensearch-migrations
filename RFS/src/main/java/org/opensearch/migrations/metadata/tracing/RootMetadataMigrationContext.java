package org.opensearch.migrations.metadata.tracing;

import io.opentelemetry.api.OpenTelemetry;

import org.opensearch.migrations.tracing.IContextTracker;

import com.rfs.tracing.BaseRootRfsContext;

public class RootMetadataMigrationContext extends BaseRootRfsContext {
    public static final String SCOPE_NAME = "metadataMigration";

    public final MetadataMigrationContexts.ClusterMetadataContext.MetricInstruments metadataMetrics;
    public final MetadataMigrationContexts.MigrateTemplateContext.MetricInstruments indexTemplateInstruments;
    public final MetadataMigrationContexts.CreateIndexContext.MetricInstruments createIndexInstruments;

    public RootMetadataMigrationContext(OpenTelemetry sdk, IContextTracker contextTracker) {
        super(SCOPE_NAME, sdk, contextTracker);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        metadataMetrics = MetadataMigrationContexts.ClusterMetadataContext.makeMetrics(meter);
        indexTemplateInstruments = MetadataMigrationContexts.MigrateTemplateContext.makeMetrics(meter);
        createIndexInstruments = MetadataMigrationContexts.CreateIndexContext.makeMetrics(meter);
    }

    public IMetadataMigrationContexts.IClusterMetadataContext createMetadataMigrationContext() {
        return new MetadataMigrationContexts.ClusterMetadataContext(this);
    }

    public IMetadataMigrationContexts.ICreateIndexContext createIndexContext() {
        return new MetadataMigrationContexts.CreateIndexContext(this);
    }
}
