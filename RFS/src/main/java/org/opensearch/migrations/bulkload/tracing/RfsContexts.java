package org.opensearch.migrations.bulkload.tracing;

import org.opensearch.migrations.tracing.BaseSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import lombok.NonNull;

public interface RfsContexts extends IRfsContexts {

    String COUNT_UNITS = "count";

    class GenericRequestContext extends BaseSpanContext<BaseRootRfsContext>
        implements
            IRfsContexts.IRequestContext {

        public static final AttributeKey<String> HTTP_METHOD_ATTR = AttributeKey.stringKey("httpMethod");
        public static final AttributeKey<Long> BYTES_READ_ATTR = AttributeKey.longKey("bytesRead");
        public static final AttributeKey<Long> BYTES_SENT_ATTR = AttributeKey.longKey("bytesSent");

        @Getter
        public final IScopedInstrumentationAttributes enclosingScope;
        private final String label;
        private int bytesRead;
        private int bytesSent;

        public GenericRequestContext(
            BaseRootRfsContext rootScope,
            IScopedInstrumentationAttributes enclosingScope,
            String label
        ) {
            super(rootScope);
            initializeSpan(rootScope);
            this.enclosingScope = enclosingScope;
            this.label = label;
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public final LongCounter bytesSentCounter;
            public final LongCounter bytesReadCounter;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                bytesSentCounter = meter.counterBuilder(MetricNames.BYTES_SENT).setUnit(COUNT_UNITS).build();
                bytesReadCounter = meter.counterBuilder(MetricNames.BYTES_READ).setUnit(COUNT_UNITS).build();
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
            return getRootInstrumentationScope().genericRequestInstruments;
        }

        public AttributesBuilder getSharedAttributes(AttributesBuilder attributesBuilder) {
            return attributesBuilder.put(HTTP_METHOD_ATTR, label);
        }

        @Override
        public AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
            return getSharedAttributes(super.fillExtraAttributesForThisSpan(builder)).put(BYTES_SENT_ATTR, bytesSent)
                .put(BYTES_READ_ATTR, bytesRead);
        }

        @Override
        public void addBytesSent(int i) {
            bytesSent += i;
            meterIncrementEvent(getMetrics().bytesSentCounter, i);
            meterIncrementEvent(getMetrics().bytesSentCounter, i, getSharedAttributes(Attributes.builder()));
        }

        @Override
        public void addBytesRead(int i) {
            bytesRead += i;
            meterIncrementEvent(getMetrics().bytesReadCounter, i);
            meterIncrementEvent(getMetrics().bytesReadCounter, i, getSharedAttributes(Attributes.builder()));
        }
    }

    class CheckedIdempotentPutRequestContext extends BaseSpanContext<BaseRootRfsContext>
        implements
            IRfsContexts.ICheckedIdempotentPutRequestContext {
        @Getter
        public final IScopedInstrumentationAttributes enclosingScope;
        private final String label;

        public CheckedIdempotentPutRequestContext(
            BaseRootRfsContext rootScope,
            IScopedInstrumentationAttributes enclosingScope,
            String label
        ) {
            super(rootScope);
            initializeSpan(rootScope);
            this.enclosingScope = enclosingScope;
            this.label = label;
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

        // If we want separate metrics, we could key off of additional attributes like uri/verb to get values
        // from maps within the root context to retrieve metric objects that are setup at runtime rather
        // than compile time
        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().getTwoStepIdempotentRequestInstruments;
        }

        @Override
        public IRfsContexts.IRequestContext createCheckRequestContext() {
            return new GenericRequestContext(rootInstrumentationScope, this, label + "createCheckRequestContext");
        }

        @Override
        public IRfsContexts.IRequestContext createPutContext() {
            return new GenericRequestContext(rootInstrumentationScope, this, label + "createPutContext");
        }

    }

    class DeltaStreamContext extends BaseSpanContext<BaseRootRfsContext>
        implements
            IRfsContexts.IDeltaStreamContext {

        public static final AttributeKey<Long> SEGMENTS_SEEN_ATTR = AttributeKey.longKey("segmentsSeen");
        public static final AttributeKey<Long> DELTA_ADDITIONS_ATTR = AttributeKey.longKey("deltaAdditions");
        public static final AttributeKey<Long> DELTA_DELETIONS_ATTR = AttributeKey.longKey("deltaDeletions");

        @Getter
        public final IScopedInstrumentationAttributes enclosingScope;
        private long segmentsSeen;
        private long deltaAdditions;
        private long deltaDeletions;

        public DeltaStreamContext(
            BaseRootRfsContext rootScope,
            IScopedInstrumentationAttributes enclosingScope
        ) {
            super(rootScope);
            initializeSpan(rootScope);
            this.enclosingScope = enclosingScope;
        }

        @Override
        public String getActivityName() {
            return ACTIVITY_NAME;
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public final LongCounter segmentsSeenCounter;
            public final LongCounter deltaAdditionsCounter;
            public final LongCounter deltaDeletionsCounter;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                segmentsSeenCounter = meter.counterBuilder("deltaSegmentsSeen").setUnit(COUNT_UNITS).build();
                deltaAdditionsCounter = meter.counterBuilder("deltaAdditions").setUnit(COUNT_UNITS).build();
                deltaDeletionsCounter = meter.counterBuilder("deltaDeletions").setUnit(COUNT_UNITS).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().deltaStreamInstruments;
        }

        @Override
        public AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
            return super.fillExtraAttributesForThisSpan(builder)
                .put(SEGMENTS_SEEN_ATTR, segmentsSeen)
                .put(DELTA_ADDITIONS_ATTR, deltaAdditions)
                .put(DELTA_DELETIONS_ATTR, deltaDeletions);
        }

        @Override
        public void recordSegmentsSeen(long count) {
            segmentsSeen += count;
            meterIncrementEvent(getMetrics().segmentsSeenCounter, count);
        }

        @Override
        public void recordDeltaAdditions(long count) {
            deltaAdditions += count;
            meterIncrementEvent(getMetrics().deltaAdditionsCounter, count);
        }

        @Override
        public void recordDeltaDeletions(long count) {
            deltaDeletions += count;
            meterIncrementEvent(getMetrics().deltaDeletionsCounter, count);
        }
    }

}
