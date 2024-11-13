package org.opensearch.migrations.reindexer.tracing;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;
import org.opensearch.migrations.bulkload.tracing.RfsContexts;
import org.opensearch.migrations.bulkload.tracing.RootWorkCoordinationContext;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public interface DocumentMigrationContexts extends IDocumentMigrationContexts {

    abstract class BaseDocumentMigrationContext extends BaseSpanContext<RootDocumentMigrationContext> {
        protected BaseDocumentMigrationContext(RootDocumentMigrationContext rootScope) {
            super(rootScope);
        }

        public RootWorkCoordinationContext getWorkCoordinationRootContext() {
            return this.rootInstrumentationScope.getWorkCoordinationContext();
        }
    }

    class ShardSetupAttemptContext extends BaseDocumentMigrationContext
        implements
            IShardSetupAttemptContext {
        protected ShardSetupAttemptContext(RootDocumentMigrationContext rootScope) {
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

                super(meter, fromActivityName(activityName));
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
            return getWorkCoordinationRootContext().createAcquireSpecificItemContext(getEnclosingScope());
        }

        @Override
        public IWorkCoordinationContexts.ICompleteWorkItemContext createWorkCompletionContext() {
            return getWorkCoordinationRootContext().createCompleteWorkContext(getEnclosingScope());
        }

        @Override
        public IAddShardWorkItemContext createShardWorkItemContext() {
            return new AddShardWorkItemContext(rootInstrumentationScope, this);
        }

        @Override
        public IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext createSuccessorWorkItemsContext() {
            return getWorkCoordinationRootContext().createSuccessorWorkItemsContext(getEnclosingScope());
        }
    }

    class AddShardWorkItemContext extends BaseNestedSpanContext<
        RootDocumentMigrationContext,
        IShardSetupAttemptContext> implements IAddShardWorkItemContext {

        protected AddShardWorkItemContext(
            RootDocumentMigrationContext rootScope,
            IShardSetupAttemptContext enclosingScope
        ) {
            super(rootScope, enclosingScope);
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, fromActivityName(activityName));
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().addShardWorkItemMetrics;
        }

        @Override
        public IWorkCoordinationContexts.ICreateUnassignedWorkItemContext createUnassignedWorkItemContext() {
            return rootInstrumentationScope.getWorkCoordinationContext()
                .createUnassignedWorkContext(getEnclosingScope());
        }

    }

    class DocumentReindexContext extends BaseDocumentMigrationContext implements IDocumentReindexContext {

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
                super(meter, fromActivityName(activityName));
            }
        }

        public static @NonNull DocumentMigrationContexts.DocumentReindexContext.MetricInstruments makeMetrics(
            Meter meter
        ) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().documentReindexInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createBulkRequest() {
            return new RfsContexts.GenericRequestContext(
                rootInstrumentationScope,
                this,
                "DocumentReindexContext.createBulkRequest"
            );
        }

        @Override
        public IRfsContexts.IRequestContext createRefreshContext() {
            return new RfsContexts.GenericRequestContext(
                rootInstrumentationScope,
                this,
                "DocumentReindexContext.createRefreshContext"
            );
        }

        @Override
        public IWorkCoordinationContexts.IAcquireNextWorkItemContext createOpeningContext() {
            return getWorkCoordinationRootContext().createAcquireNextItemContext();
        }

        @Override
        public IWorkCoordinationContexts.ICompleteWorkItemContext createCloseContet() {
            return getWorkCoordinationRootContext().createCompleteWorkContext();
        }

        @Override
        public IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext createSuccessorWorkItemsContext() {
            return getWorkCoordinationRootContext().createSuccessorWorkItemsContext();
        }
    }
}
