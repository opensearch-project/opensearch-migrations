package org.opensearch.migrations.transform.shim.tracing;

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public class TransformContext
    extends BaseNestedSpanContext<RootShimProxyContext, TargetDispatchContext>
    implements IShimProxyContexts.ITransformContext {

    public static final AttributeKey<String> TRANSFORM_DIRECTION_ATTR = AttributeKey.stringKey("transform.direction");
    public static final AttributeKey<String> TARGET_NAME_ATTR = AttributeKey.stringKey("target.name");

    private final String direction;
    private final String targetName;

    public TransformContext(@NonNull TargetDispatchContext parentContext, String direction) {
        super(parentContext.getRootInstrumentationScope(), parentContext);
        this.direction = direction;
        this.targetName = parentContext.getTargetName();
        initializeSpan();
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

    public static MetricInstruments makeMetrics(Meter meter) {
        return new MetricInstruments(meter, ACTIVITY_NAME);
    }

    @Override
    public MetricInstruments getMetrics() {
        return getRootInstrumentationScope().transformInstruments;
    }

    @Override
    public AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
        return super.fillExtraAttributesForThisSpan(builder)
            .put(TRANSFORM_DIRECTION_ATTR, direction)
            .put(TARGET_NAME_ATTR, targetName);
    }

    @Override
    public @NonNull Attributes getPopulatedMetricAttributes(AttributesBuilder attributesBuilder) {
        return super.getPopulatedMetricAttributes(
            attributesBuilder
                .put(TRANSFORM_DIRECTION_ATTR, direction)
                .put(TARGET_NAME_ATTR, targetName));
    }
}
