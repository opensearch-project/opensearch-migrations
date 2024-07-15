package org.opensearch.migrations.replay.tracing;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import lombok.Lombok;

public class TracingTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    public void tracingWorks() {
        var tssk = new ISourceTrafficChannelKey.PojoImpl("n", "c");
        try (
            var channelCtx = rootContext.createChannelContext(tssk);
            var kafkaRecordCtx = rootContext.createTrafficStreamContextForKafkaSource(channelCtx, "testRecordId", 127)
        ) {
            var tsk = PojoTrafficStreamKeyAndContext.build(tssk, 1, kafkaRecordCtx::createTrafficLifecyleContext);
            try (var tskCtx = tsk.getTrafficStreamsContext()) { // made in the callback of the previous call
                var urk = new UniqueReplayerRequestKey(tsk, 1, 0);
                try (var httpCtx = tskCtx.createHttpTransactionContext(urk, Instant.EPOCH)) {
                    try (var ctx = httpCtx.createRequestAccumulationContext()) {}
                    try (var ctx = httpCtx.createResponseAccumulationContext()) {}
                    try (var ctx = httpCtx.createTransformationContext()) {}
                    try (var ctx = httpCtx.createScheduledContext(Instant.now())) {}
                    try (var targetRequestCtx = httpCtx.createTargetRequestContext()) {
                        try (var ctx = targetRequestCtx.createHttpSendingContext()) {}
                        try (var ctx = targetRequestCtx.createWaitingForResponseContext()) {}
                        try (var ctx = targetRequestCtx.createHttpReceivingContext()) {}
                    }
                    try (var ctx = httpCtx.createTupleContext()) {}
                }
            }
            try (var ctx = channelCtx.createSocketContext()) {}
        }

        var recordedSpans = rootContext.inMemoryInstrumentationBundle.getFinishedSpans();
        var recordedMetrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();

        checkSpans(recordedSpans);
        checkMetrics(recordedMetrics);

        Assertions.assertTrue(rootContext.getBacktracingContextTracker().getAllRemainingActiveScopes().isEmpty());
    }

    private void checkMetrics(Collection<MetricData> recordedMetrics) {}

    private void checkSpans(List<SpanData> recordedSpans) {
        var byName = recordedSpans.stream().collect(Collectors.groupingBy(SpanData::getName));
        var keys = Arrays.stream(IReplayContexts.ActivityNames.class.getFields()).map(f -> {
            try {
                return f.get(null);
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).toArray(String[]::new);
        Stream.of(keys).forEach(spanName -> {
            Assertions.assertNotNull(byName.get(spanName));
            Assertions.assertEquals(1, byName.get(spanName).size());
            byName.remove(spanName);
        });

        Assertions.assertEquals(
            "",
            byName.entrySet().stream().map(kvp -> kvp.getKey() + ":" + kvp.getValue()).collect(Collectors.joining())
        );
    }
}
