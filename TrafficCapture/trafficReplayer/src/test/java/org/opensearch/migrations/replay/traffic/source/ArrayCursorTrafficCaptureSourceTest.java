package org.opensearch.migrations.replay.traffic.source;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.BacktracingContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArrayCursorTrafficCaptureSourceTest {
    private static final int SOURCE_GENERATION = 7;

    @Test
    void completionAdvancesOnlyAcrossTheObservedHead() throws Exception {
        try (var rootContext = TestContext.noOtelTracking()) {
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
                trafficStream("first"),
                trafficStream("second")
            ), SOURCE_GENERATION);
            var source = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);
            var first = readRecord(source);
            var second = readRecord(source);

            source.recordProcessingFinished(second).toCompletableFuture().join();
            Assertions.assertEquals(
                0,
                sourceContext.nextReadCursor.get(),
                "a completed later record must remain blocked behind the observed head"
            );

            source.recordProcessingFinished(first).toCompletableFuture().join();
            Assertions.assertEquals(2, sourceContext.nextReadCursor.get());
        }
    }

    @Test
    void staleGenerationCompletionIsIgnoredWithoutMutatingTheCursor() throws Exception {
        try (var rootContext = TestContext.noOtelTracking()) {
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
                trafficStream("only")
            ), SOURCE_GENERATION);
            var source = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);
            var record = readRecord(source);
            var stale = new KafkaRecordId(
                record.topic(),
                record.partition(),
                record.offset(),
                record.sourceGeneration() - 1
            );

            source.recordProcessingFinished(stale).toCompletableFuture().join();
            Assertions.assertEquals(0, sourceContext.nextReadCursor.get());

            source.recordProcessingFinished(record).toCompletableFuture().join();
            Assertions.assertEquals(1, sourceContext.nextReadCursor.get());
        }
    }

    @Test
    void invalidIdentityReturnsFailedAcknowledgementWithoutThrowing() throws Exception {
        try (var rootContext = TestContext.noOtelTracking()) {
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
                trafficStream("only")
            ), SOURCE_GENERATION);
            var source = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);
            var record = readRecord(source);
            var invalid = new KafkaRecordId(
                "unexpected-topic",
                record.partition(),
                record.offset(),
                record.sourceGeneration()
            );

            var acknowledgement = Assertions.assertDoesNotThrow(
                () -> source.recordProcessingFinished(invalid)
            );
            var failure = Assertions.assertThrows(
                CompletionException.class,
                () -> acknowledgement.toCompletableFuture().join()
            );
            Assertions.assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            Assertions.assertEquals(0, sourceContext.nextReadCursor.get());

            source.recordProcessingFinished(record).toCompletableFuture().join();
        }
    }

    @Test
    void contextReleaseFailureReturnsFailedAcknowledgementWithoutThrowing() throws Exception {
        try (var rootContext = new TestContext(
            new InMemoryInstrumentationBundle(null, null),
            new BacktracingContextTracker()
        ) {
            private boolean failNextRelease = true;

            @Override
            public IReplayContexts.IChannelKeyContext releaseChannelContextForTest(
                IReplayContexts.IChannelKeyContext context
            ) {
                if (failNextRelease) {
                    failNextRelease = false;
                    throw new IllegalStateException("injected context release failure");
                }
                return super.releaseChannelContextForTest(context);
            }
        }) {
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
                trafficStream("only")
            ), SOURCE_GENERATION);
            var source = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);
            var record = readRecord(source);

            var acknowledgement = Assertions.assertDoesNotThrow(
                () -> source.recordProcessingFinished(record)
            );
            var failure = Assertions.assertThrows(
                CompletionException.class,
                () -> acknowledgement.toCompletableFuture().join()
            );
            Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
            Assertions.assertEquals(0, sourceContext.nextReadCursor.get());
            Assertions.assertEquals(1, source.pQueue.size());

            source.recordProcessingFinished(record).toCompletableFuture().join();
            Assertions.assertEquals(1, sourceContext.nextReadCursor.get());
            Assertions.assertTrue(source.pQueue.isEmpty());
        }
    }

    @Test
    void sourceRestartAdvancesGenerationAndRetainsCommittedCursor() throws Exception {
        var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
            trafficStream("first"),
            trafficStream("second")
        ), SOURCE_GENERATION);
        try (var firstRoot = TestContext.noOtelTracking()) {
            var firstSource =
                (ArrayCursorTrafficCaptureSource) sourceContext.apply(firstRoot);
            var firstRecord = readRecord(firstSource);
            Assertions.assertEquals(SOURCE_GENERATION, firstRecord.sourceGeneration());
            firstSource.recordProcessingFinished(firstRecord).toCompletableFuture().join();
        }

        try (var secondRoot = TestContext.noOtelTracking()) {
            var secondSource =
                (ArrayCursorTrafficCaptureSource) sourceContext.apply(secondRoot);
            var secondRecord = readRecord(secondSource);
            Assertions.assertEquals(1, secondRecord.offset());
            Assertions.assertEquals(SOURCE_GENERATION + 1, secondRecord.sourceGeneration());
        }
    }

    @Test
    void completionFromSupersededSourceInstanceIsIgnored() throws Exception {
        var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
            trafficStream("only")
        ), SOURCE_GENERATION);
        try (var rootContext = TestContext.noOtelTracking()) {
            var firstSource =
                (ArrayCursorTrafficCaptureSource) sourceContext.apply(rootContext);
            var staleRecord = readRecord(firstSource);
            var activeSource =
                (ArrayCursorTrafficCaptureSource) sourceContext.apply(rootContext);
            var activeRecord = readRecord(activeSource);
            Assertions.assertTrue(firstSource.pQueue.isEmpty());

            firstSource.recordProcessingFinished(staleRecord).toCompletableFuture().join();
            Assertions.assertEquals(0, sourceContext.nextReadCursor.get());
            Assertions.assertThrows(
                CancellationException.class,
                () -> firstSource.readNextTrafficStreamChunk(() -> null).join()
            );

            activeSource.recordProcessingFinished(activeRecord).toCompletableFuture().join();
            Assertions.assertEquals(1, sourceContext.nextReadCursor.get());
        }
    }

    @Test
    void closeReleasesPendingContextsAndRejectsLaterCompletion() throws Exception {
        try (var rootContext = TestContext.noOtelTracking()) {
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(
                trafficStream("only")
            ), SOURCE_GENERATION);
            var source = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);
            var record = readRecord(source);

            source.close();
            Assertions.assertTrue(source.pQueue.isEmpty());
            Assertions.assertEquals(0, sourceContext.nextReadCursor.get());

            var failure = Assertions.assertThrows(
                CompletionException.class,
                () -> source.recordProcessingFinished(record).toCompletableFuture().join()
            );
            Assertions.assertInstanceOf(CancellationException.class, failure.getCause());
            Assertions.assertEquals(0, sourceContext.nextReadCursor.get());
        }
    }

    private static KafkaRecordId readRecord(ArrayCursorTrafficCaptureSource source) {
        var input = source.readNextTrafficStreamChunk(() -> null).join().get(0);
        var stream = (ITrafficStreamWithKey) input;
        return (KafkaRecordId) source.recordIdFor(stream.getKey());
    }

    private static TrafficStream trafficStream(String connectionId) {
        return TrafficStream.newBuilder()
            .setNodeId("node")
            .setConnectionId(connectionId)
            .build();
    }
}
