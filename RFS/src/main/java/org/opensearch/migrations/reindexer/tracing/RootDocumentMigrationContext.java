package org.opensearch.migrations.reindexer.tracing;

import io.opentelemetry.api.OpenTelemetry;

import org.opensearch.migrations.tracing.IContextTracker;

import com.rfs.tracing.BaseRootRfsContext;
import com.rfs.tracing.RootWorkCoordinationContext;
import lombok.Getter;

public class RootDocumentMigrationContext extends BaseRootRfsContext implements IRootDocumentMigrationContext {
    public static final String SCOPE_NAME = "documentMigration";

    @Getter
    private final RootWorkCoordinationContext workCoordinationContext;
    public final DocumentMigrationContexts.DocumentReindexContext.MetricInstruments documentReindexInstruments;
    public final DocumentMigrationContexts.ShardSetupAttemptContext.MetricInstruments shardSetupMetrics;
    public final DocumentMigrationContexts.AddShardWorkItemContext.MetricInstruments addShardWorkItemMetrics;

    public RootDocumentMigrationContext(
        OpenTelemetry sdk,
        IContextTracker contextTracker,
        RootWorkCoordinationContext workCoordinationContext
    ) {
        super(SCOPE_NAME, sdk, contextTracker);
        var meter = this.getMeterProvider().get(SCOPE_NAME);
        this.workCoordinationContext = workCoordinationContext;
        documentReindexInstruments = DocumentMigrationContexts.DocumentReindexContext.makeMetrics(meter);
        shardSetupMetrics = DocumentMigrationContexts.ShardSetupAttemptContext.makeMetrics(meter);
        addShardWorkItemMetrics = DocumentMigrationContexts.AddShardWorkItemContext.makeMetrics(meter);
    }

    @Override
    public IDocumentMigrationContexts.IShardSetupAttemptContext createDocsMigrationSetupContext() {
        return new DocumentMigrationContexts.ShardSetupAttemptContext(this);
    }

    @Override
    public IDocumentMigrationContexts.IDocumentReindexContext createReindexContext() {
        return new DocumentMigrationContexts.DocumentReindexContext(this);
    }

}
