package org.opensearch.migrations.transform.shim.tracing;

import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public class ShimRequestContext extends BaseSpanContext<RootShimProxyContext>
    implements IShimProxyContexts.IRequestContext {

    public static final AttributeKey<String> HTTP_METHOD_ATTR = AttributeKey.stringKey("http.method");
    public static final AttributeKey<String> HTTP_URL_ATTR = AttributeKey.stringKey("http.url");

    private final String httpMethod;
    private final String httpUrl;

    public ShimRequestContext(@NonNull RootShimProxyContext rootScope, String httpMethod, String httpUrl) {
        super(rootScope);
        this.httpMethod = httpMethod;
        this.httpUrl = httpUrl;
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

    public static MetricInstruments makeMetrics(Meter meter) {
        return new MetricInstruments(meter, ACTIVITY_NAME);
    }

    @Override
    public MetricInstruments getMetrics() {
        return getRootInstrumentationScope().shimRequestInstruments;
    }

    @Override
    public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
        return builder.put(HTTP_METHOD_ATTR, httpMethod).put(HTTP_URL_ATTR, httpUrl);
    }

    @Override
    public AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
        return super.fillExtraAttributesForThisSpan(builder)
            .put(HTTP_METHOD_ATTR, httpMethod)
            .put(HTTP_URL_ATTR, httpUrl);
    }

    @Override
    public IShimProxyContexts.ITargetDispatchContext createTargetDispatchContext(String targetName) {
        return new TargetDispatchContext(this, targetName);
    }
}
