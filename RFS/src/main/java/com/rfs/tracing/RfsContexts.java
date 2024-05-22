package com.rfs.tracing;

import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonMetricInstruments;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public class RfsContexts {

    private RfsContexts() {}

    public static final String COUNT_UNITS = "count";

    public static class GenericRequestContext
            extends BaseSpanContext<RootRfsContext>
            implements IRfsContexts.IRequestContext {
        @Getter
        public final IScopedInstrumentationAttributes enclosingScope;
        private final String label;

        public GenericRequestContext(RootRfsContext rootScope,
                                     IScopedInstrumentationAttributes enclosingScope,
                                     String label) {
            super(rootScope);
            initializeSpan(rootScope);
            this.enclosingScope = enclosingScope;
            this.label = label;
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        // If we want separate metrics, we could key off of additional attributes like uri/verb to get values
        // from maps within the root context to retrieve metric objects that are setup at runtime rather
        // than compile time
        @Override
        public CommonScopedMetricInstruments getMetrics() {
            return getRootInstrumentationScope().genericRequestInstruments;
        }
    }

    public static class CheckedIdempotentPutRequestContext
            extends BaseSpanContext<RootRfsContext>
            implements IRfsContexts.ICheckedIdempotentPutRequestContext {
        @Getter
        public final IScopedInstrumentationAttributes enclosingScope;
        private final String label;

        public CheckedIdempotentPutRequestContext(RootRfsContext rootScope,
                                                  IScopedInstrumentationAttributes enclosingScope,
                                                  String label) {
            super(rootScope);
            initializeSpan(rootScope);
            this.enclosingScope = enclosingScope;
            this.label = label;
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        // If we want separate metrics, we could key off of additional attributes like uri/verb to get values
        // from maps within the root context to retrieve metric objects that are setup at runtime rather
        // than compile time
        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().getTwoStepIdempotentRequestInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createCheckRequestContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    label+"createCheckRequestContext");
        }

        @Override
        public IRfsContexts.IRequestContext createPutContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    label+"createPutContext");
        }

    }

    public static class CreateSnapshotContext
            extends BaseSpanContext<RootRfsContext>
            implements IRfsContexts.ICreateSnapshotContext {

        protected CreateSnapshotContext(RootRfsContext rootScope) {
            super(rootScope);
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

        @Override
        public IScopedInstrumentationAttributes getEnclosingScope() {
            return null;
        }

        @Override
        public IRfsContexts.IRequestContext createRegisterRequest() {
            return new GenericRequestContext(rootInstrumentationScope, this, "createRegisterRequest");
        }

        @Override
        public IRfsContexts.IRequestContext createSnapshotContext() {
            return new GenericRequestContext(rootInstrumentationScope, this, "createSnapshotContext");
        }

        @Override
        public IRfsContexts.IRequestContext createGetSnapshotContext() {
            return new GenericRequestContext(rootInstrumentationScope, this, "createGetSnapshotContext");
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

    public static class ClusterMetadataContext
            extends BaseSpanContext<RootRfsContext>
            implements IRfsContexts.IClusterMetadataContext {

        protected ClusterMetadataContext(RootRfsContext rootScope) {
            super(rootScope);
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

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
            return getRootInstrumentationScope().metadataMetrics;
        }

        @Override
        public IRfsContexts.IIndexTemplateContext createMigrateLegacyIndexTemplateContext() {
            return new MigrateIndexTemplateContext(rootInstrumentationScope, this);
        }

        @Override
        public IRfsContexts.ICheckedIdempotentPutRequestContext createComponentTemplateContext() {
            return new CheckedIdempotentPutRequestContext(rootInstrumentationScope, this,
                    "createComponentTemplateContext");
        }

        @Override
        public IRfsContexts.ICheckedIdempotentPutRequestContext createMigrateIndexTemplateContext() {
            return new CheckedIdempotentPutRequestContext(rootInstrumentationScope, this,
                    "createGetSnapshotContext");
        }
    }

    public static class MigrateIndexTemplateContext
            extends BaseNestedSpanContext<RootRfsContext, IRfsContexts.IClusterMetadataContext>
            implements IRfsContexts.IIndexTemplateContext {

        protected MigrateIndexTemplateContext(RootRfsContext rootScope,
                                              IRfsContexts.IClusterMetadataContext enclosingScope) {
            super(rootScope, enclosingScope);
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

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
            return getRootInstrumentationScope().indexTemplateInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createCheckRequestContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    "createCheckRequestContext");
        }

        @Override
        public IRfsContexts.IRequestContext createPutContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    "createPutContext");
        }
    }

    public static class CreateIndexContext
            extends BaseSpanContext<RootRfsContext>
            implements IRfsContexts.ICreateIndexContext {

        protected CreateIndexContext(RootRfsContext rootScope) {
            super(rootScope);
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

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
            return getRootInstrumentationScope().createIndexInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createCheckRequestContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    "CreateIndexContext.createCheckRequestContext");
        }

        @Override
        public IRfsContexts.IRequestContext createPutContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    "CreateIndexContext.createPutContext");
        }
    }

    public static class DocumentReindexContext
            extends BaseSpanContext<RootRfsContext>
            implements IRfsContexts.IDocumentReindexContext {

        protected DocumentReindexContext(RootRfsContext rootScope) {
            super(rootScope);
            initializeSpan(rootScope);
        }

        @Override
        public String getActivityName() { return ACTIVITY_NAME; }

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
            return getRootInstrumentationScope().documentReindexInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createBulkRequest() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    "DocumentReindexContext.createBulkRequest");
        }

        @Override
        public IRfsContexts.IRequestContext createRefreshContext() {
            return new GenericRequestContext(rootInstrumentationScope, this,
                    "DocumentReindexContext.createRefreshContext");
        }

    }

    public static class WorkingStateContext
            implements IRfsContexts.IWorkingStateContext {
        @Getter
        @NonNull
        public final RootRfsContext rootInstrumentationScope; // TODO - rename this to rootScope
        @Getter
        Exception observedExceptionToIncludeInMetrics;

        public WorkingStateContext(@NonNull RootRfsContext rootScope) {
            this.rootInstrumentationScope = rootScope;
        }

        @Override
        public IInstrumentationAttributes getEnclosingScope() {
            return null;
        }

        public static class MetricInstruments extends CommonMetricInstruments {
            private MetricInstruments(Meter meter) {
                super(meter, "ungroupedWorkingStateUpdates");
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter);
        }

        @NonNull public MetricInstruments getMetrics() {
            return rootInstrumentationScope.workingStateUpdateInstruments;
        }

        private GenericRequestContext makeGenericRequestContextForWorkingState(String label) {
            return new GenericRequestContext(rootInstrumentationScope, null, label);
        }
        
        @Override
        public IRfsContexts.IRequestContext createGetSnapshotEntryContext() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.getSnapshotEntry");
        }

        @Override
        public IRfsContexts.IRequestContext createCreateSnapshotEntryDocumentContext() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.createSnapshotEntry");
        }

        @Override
        public IRfsContexts.IRequestContext createUpdateSnapshotEntryContext() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.updateSnapshotEntry");
        }

        @Override
        public IRfsContexts.IRequestContext createCreateMetadataEntryDocumentContext() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.createMetadataEntry");
        }

        @Override
        public IRfsContexts.IRequestContext createGetMetadataEntryDocument() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.getMetadataEntry");
        }

        @Override
        public IRfsContexts.IRequestContext createInitialMetadataMigrationStatusDocumentContext() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.initialMetadataMigrationStatus");
        }

        @Override
        public IRfsContexts.IRequestContext createUpdateMetadataMigrationStatusDocumentContext() {
            return makeGenericRequestContextForWorkingState("WorkingStateContext.updateMetadataMigrationStatus");
        }
    }

}
