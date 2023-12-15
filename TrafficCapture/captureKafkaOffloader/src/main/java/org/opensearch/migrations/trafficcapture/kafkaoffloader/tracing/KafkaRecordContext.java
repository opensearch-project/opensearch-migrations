package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.IWithAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;

import java.time.Instant;

@AllArgsConstructor
public class KafkaRecordContext implements IWithAttributes, IWithStartTime {
    static final AttributeKey<String> TOPIC_ATTR = AttributeKey.stringKey("topic");
    static final AttributeKey<String> RECORD_ID_ATTR = AttributeKey.stringKey("recordId");
    static final AttributeKey<Long> RECORD_SIZE_ATTR = AttributeKey.longKey("recordSize");

    @Getter
    public final IConnectionContext enclosingScope;
    @Getter
    public final Span currentSpan;
    @Getter
    public final Instant startTime;
    @Getter
    public final String topic;
    @Getter
    public final String recordId;
    @Getter
    public final int recordSize;

    public KafkaRecordContext(IConnectionContext enclosingScope, ISpanWithParentGenerator incomingSpan,
                              String topic, String recordId, int recordSize) {
        this.enclosingScope = enclosingScope;
        this.topic = topic;
        this.recordId = recordId;
        this.recordSize = recordSize;
        this.startTime = Instant.now();
        currentSpan = incomingSpan.apply(this.getPopulatedAttributes(), enclosingScope.getCurrentSpan());
    }

    @Override
    public AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(TOPIC_ATTR, getTopic())
                .put(RECORD_ID_ATTR, getRecordId())
                .put(RECORD_SIZE_ATTR, getRecordSize());
    }
}
