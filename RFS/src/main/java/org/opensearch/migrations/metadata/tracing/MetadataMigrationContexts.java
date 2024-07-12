package org.opensearch.migrations.metadata.tracing;

import io.opentelemetry.api.metrics.Meter;

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.RfsContexts;
import lombok.NonNull;

public class MetadataMigrationContexts {
    public static class ClusterMetadataContext extends BaseSpanContext<RootMetadataMigrationContext>
        implements
            IMetadataMigrationContexts.IClusterMetadataContext {

        protected ClusterMetadataContext(RootMetadataMigrationContext rootScope) {
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

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().metadataMetrics;
        }

        @Override
        public IMetadataMigrationContexts.ITemplateContext createMigrateLegacyTemplateContext() {
            return new MigrateTemplateContext(rootInstrumentationScope, this);
        }

        @Override
        public IRfsContexts.ICheckedIdempotentPutRequestContext createComponentTemplateContext() {
            return new RfsContexts.CheckedIdempotentPutRequestContext(
                rootInstrumentationScope,
                this,
                "createComponentTemplateContext"
            );
        }

        @Override
        public IRfsContexts.ICheckedIdempotentPutRequestContext createMigrateTemplateContext() {
            return new RfsContexts.CheckedIdempotentPutRequestContext(
                rootInstrumentationScope,
                this,
                "createGetSnapshotContext"
            );
        }
    }

    public static class MigrateTemplateContext extends BaseNestedSpanContext<
        RootMetadataMigrationContext,
        IMetadataMigrationContexts.IClusterMetadataContext> implements IMetadataMigrationContexts.ITemplateContext {

        protected MigrateTemplateContext(
            RootMetadataMigrationContext rootScope,
            IMetadataMigrationContexts.IClusterMetadataContext enclosingScope
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
            return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this, "createCheckRequestContext");
        }

        @Override
        public IRfsContexts.IRequestContext createPutContext() {
            return new RfsContexts.GenericRequestContext(rootInstrumentationScope, this, "createPutContext");
        }
    }

    public static class CreateIndexContext extends BaseSpanContext<RootMetadataMigrationContext>
        implements
            IMetadataMigrationContexts.ICreateIndexContext {

        protected CreateIndexContext(RootMetadataMigrationContext rootScope) {
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

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().createIndexInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createCheckRequestContext() {
            return new RfsContexts.GenericRequestContext(
                rootInstrumentationScope,
                this,
                "CreateIndexContext.createCheckRequestContext"
            );
        }

        @Override
        public IRfsContexts.IRequestContext createPutContext() {
            return new RfsContexts.GenericRequestContext(
                rootInstrumentationScope,
                this,
                "CreateIndexContext.createPutContext"
            );
        }
    }
}
