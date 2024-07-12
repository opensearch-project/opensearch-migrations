package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;

import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import lombok.Getter;
import lombok.NonNull;

public class KafkaRecordContext extends BaseNestedSpanContext<IRootKafkaOffloaderContext, IConnectionContext>
    implements
        IScopedInstrumentationAttributes {
    public static final String ACTIVITY_NAME = "kafkaCommit";

    static final AttributeKey<String> TOPIC_ATTR = AttributeKey.stringKey("topic");
    static final AttributeKey<String> RECORD_ID_ATTR = AttributeKey.stringKey("recordId");
    static final AttributeKey<Long> RECORD_SIZE_ATTR = AttributeKey.longKey("recordSize");

    @Getter
    public final String topic;
    @Getter
    public final String recordId;

    public KafkaRecordContext(
        IRootKafkaOffloaderContext rootScope,
        IConnectionContext enclosingScope,
        String topic,
        String recordId,
        int recordSize
    ) {
        super(rootScope, enclosingScope);
        this.topic = topic;
        this.recordId = recordId;
        initializeSpan();
        this.setTraceAttribute(RECORD_SIZE_ATTR, recordSize);
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
    public @NonNull MetricInstruments getMetrics() {
        return getRootInstrumentationScope().getKafkaOffloadingInstruments();
    }

    @Override
    public String getActivityName() {
        return "stream_flush_called";
    }

    @Override
    public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
        return super.fillAttributesForSpansBelow(builder).put(TOPIC_ATTR, getTopic())
            .put(RECORD_ID_ATTR, getRecordId());
    }
}
