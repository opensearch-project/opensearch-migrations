package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest ISourceTrafficChannelKey PojoTrafficStreamKeyAndContext TestContext UniqueReplayerRequestKey . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.Lombok;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
                        try (var ctx = targetRequestCtx.createHttpConnectingContext()) {}
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
            Assertions.assertNotNull(byName.get(spanName), "\"" + spanName + "\" not present");
            Assertions.assertEquals(1, byName.get(spanName).size());
            byName.remove(spanName);
        });

        Assertions.assertEquals(
            "",
            byName.entrySet().stream().map(kvp -> kvp.getKey() + ":" + kvp.getValue()).collect(Collectors.joining())
        );
    }
}

*/
// REBUILD-LIMBO-END(G10)