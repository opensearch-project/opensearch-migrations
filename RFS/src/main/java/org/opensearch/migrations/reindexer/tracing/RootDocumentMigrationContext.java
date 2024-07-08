package org.opensearch.migrations.reindexer.tracing;

import com.rfs.tracing.BaseRootRfsContext;
import com.rfs.tracing.RootWorkCoordinationContext;
import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import org.opensearch.migrations.tracing.IContextTracker;

public class RootDocumentMigrationContext extends BaseRootRfsContext implements IRootDocumentMigrationContext {
    public static final String SCOPE_NAME = "snapshotDocumentReindex";

    @Getter
    public final RootWorkCoordinationContext workCoordinationContext;
    public final DocumentMigrationContexts.DocumentReindexContext.MetricInstruments documentReindexInstruments;
    public final DocumentMigrationContexts.ShardSetupContext.MetricInstruments shardSetupMetrics;


    public RootDocumentMigrationContext(OpenTelemetry sdk, IContextTracker contextTracker,
                                        RootWorkCoordinationContext workCoordinationContext) {
        super(sdk, contextTracker);
        var meter = this.getMeterProvider().get(SCOPE_NAME);
        this.workCoordinationContext = workCoordinationContext;
        documentReindexInstruments = DocumentMigrationContexts.DocumentReindexContext.makeMetrics(meter);
        shardSetupMetrics = DocumentMigrationContexts.ShardSetupContext.makeMetrics(meter);
    }

    @Override
    public IDocumentMigrationContexts.IShardSetupContext createDocsMigrationSetupContext() {
        return new DocumentMigrationContexts.ShardSetupContext(this, workCoordinationContext);
    }

    @Override
    public IDocumentMigrationContexts.IDocumentReindexContext createReindexContext() {
        return new DocumentMigrationContexts.DocumentReindexContext(this);
    }

}
