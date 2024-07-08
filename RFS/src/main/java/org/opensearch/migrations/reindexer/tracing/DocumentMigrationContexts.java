package org.opensearch.migrations.reindexer.tracing;

import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.IWorkCoordinationContexts;
import com.rfs.tracing.RfsContexts;
import com.rfs.tracing.RootWorkCoordinationContext;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;
import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public class DocumentMigrationContexts extends IDocumentMigrationContexts {

    public static class ShardSetupContext
        extends BaseSpanContext<RootDocumentMigrationContext>
        implements IShardSetupContext
    {
        private final RootWorkCoordinationContext workCoordinatorScope;

        protected ShardSetupContext(RootDocumentMigrationContext rootScope,
                                    RootWorkCoordinationContext workCoordinatorScope) {
            super(rootScope);
            this.workCoordinatorScope = workCoordinatorScope;
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
            return getRootInstrumentationScope().shardSetupMetrics;
        }

        @Override
        public IWorkCoordinationContexts.IAcquireSpecificWorkContext createWorkAcquisitionContext() {
            return workCoordinatorScope.createAcquireSpecificItemContext(getEnclosingScope());
        }

        @Override
        public IWorkCoordinationContexts.ICompleteWorkItemContext createWorkCompletionContext() {
            return workCoordinatorScope.createCompleteWorkContext(getEnclosingScope());
        }

        @Override
        public IWorkCoordinationContexts.ICreateUnassignedWorkItemContext createShardWorkItemContext() {
            return workCoordinatorScope.createUnassignedWorkContext(getEnclosingScope());
        }
    }

    public static class DocumentReindexContext
            extends BaseSpanContext<RootDocumentMigrationContext>
            implements IDocumentReindexContext {

        protected DocumentReindexContext(RootDocumentMigrationContext rootScope) {
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

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull DocumentMigrationContexts.DocumentReindexContext.MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().documentReindexInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createBulkRequest() {
            return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this,
                    "DocumentReindexContext.createBulkRequest");
        }

        @Override
        public IRfsContexts.IRequestContext createRefreshContext() {
            return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this,
                    "DocumentReindexContext.createRefreshContext");
        }

    }
}
