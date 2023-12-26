package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;

public class KafkaRecordContext extends DirectNestedSpanContext<IConnectionContext>
        implements IScopedInstrumentationAttributes, IWithStartTime {
    static final AttributeKey<String> TOPIC_ATTR = AttributeKey.stringKey("topic");
    static final AttributeKey<String> RECORD_ID_ATTR = AttributeKey.stringKey("recordId");
    static final AttributeKey<Long> RECORD_SIZE_ATTR = AttributeKey.longKey("recordSize");

    @Getter
    public final String topic;
    @Getter
    public final String recordId;
    @Getter
    public final int recordSize;

    public KafkaRecordContext(IConnectionContext enclosingScope, String topic, String recordId, int recordSize) {
        super(enclosingScope);
        this.topic = topic;
        this.recordId = recordId;
        this.recordSize = recordSize;
        setCurrentSpan();
    }

    @Override public String getScopeName() { return "KafkaCapture"; }

    @Override
    public String getActivityName() { return "stream_flush_called"; }

    @Override
    public AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(TOPIC_ATTR, getTopic())
                .put(RECORD_ID_ATTR, getRecordId())
                .put(RECORD_SIZE_ATTR, getRecordSize());
    }
}
