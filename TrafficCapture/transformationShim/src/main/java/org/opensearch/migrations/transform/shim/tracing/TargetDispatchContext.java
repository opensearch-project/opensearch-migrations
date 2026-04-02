package org.opensearch.migrations.transform.shim.tracing;

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public class TargetDispatchContext
    extends BaseNestedSpanContext<RootShimProxyContext, ShimRequestContext>
    implements IShimProxyContexts.ITargetDispatchContext {

    public static final AttributeKey<String> TARGET_NAME_ATTR = AttributeKey.stringKey("target.name");
    public static final AttributeKey<Long> HTTP_STATUS_CODE_ATTR = AttributeKey.longKey("http.status_code");
    public static final AttributeKey<Long> BYTES_SENT_ATTR = AttributeKey.longKey("targetBytesSent");
    public static final AttributeKey<Long> BYTES_RECEIVED_ATTR = AttributeKey.longKey("targetBytesReceived");

    private final String targetName;
    private int bytesSent;
    private int bytesReceived;
    private int statusCode;

    public TargetDispatchContext(@NonNull ShimRequestContext parentContext, String targetName) {
        super(parentContext.getRootInstrumentationScope(), parentContext);
        this.targetName = targetName;
        initializeSpan();
    }

    @Override
    public String getActivityName() {
        return ACTIVITY_NAME;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public static class MetricInstruments extends CommonScopedMetricInstruments {
        public final LongCounter targetBytesSentCounter;
        public final LongCounter targetBytesReceivedCounter;

        private MetricInstruments(Meter meter, String activityName) {
            super(meter, activityName);
            targetBytesSentCounter = meter.counterBuilder(
                IShimProxyContexts.MetricNames.TARGET_BYTES_SENT).setUnit("By").build();
            targetBytesReceivedCounter = meter.counterBuilder(
                IShimProxyContexts.MetricNames.TARGET_BYTES_RECEIVED).setUnit("By").build();
        }
    }

    public static MetricInstruments makeMetrics(Meter meter) {
        return new MetricInstruments(meter, ACTIVITY_NAME);
    }

    @Override
    public MetricInstruments getMetrics() {
        return getRootInstrumentationScope().targetDispatchInstruments;
    }

    @Override
    public AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
        return super.fillExtraAttributesForThisSpan(builder)
            .put(TARGET_NAME_ATTR, targetName)
            .put(HTTP_STATUS_CODE_ATTR, (long) statusCode)
            .put(BYTES_SENT_ATTR, (long) bytesSent)
            .put(BYTES_RECEIVED_ATTR, (long) bytesReceived);
    }

    @Override
    public @NonNull Attributes getPopulatedMetricAttributes(AttributesBuilder attributesBuilder) {
        return super.getPopulatedMetricAttributes(
            attributesBuilder
                .put(TARGET_NAME_ATTR, targetName)
                .put(HTTP_STATUS_CODE_ATTR, (long) statusCode));
    }

    @Override
    public void addBytesSent(int bytes) {
        bytesSent += bytes;
        meterIncrementEvent(getMetrics().targetBytesSentCounter, bytes);
    }

    @Override
    public void addBytesReceived(int bytes) {
        bytesReceived += bytes;
        meterIncrementEvent(getMetrics().targetBytesReceivedCounter, bytes);
    }

    @Override
    public IShimProxyContexts.ITransformContext createTransformContext(String direction) {
        return new TransformContext(this, direction);
    }
}
