/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.sink;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;
import org.opensearch.migrations.replay.testing.TestLogAppender;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.tracing.TupleDurabilityMetrics;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.kafka.common.TopicPartition;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TupleWriterTest {
    private static final ReplayRequestId REQUEST_ID = new ReplayRequestId(
        new ConnectionProcessingId(
            new PartitionGenerationId(new TopicPartition("traffic", 0), 1),
            new CapturedConnectionId("node", "connection"),
            1
        ),
        0
    );

    @Test
    void randomizedExponentialBackoffUsesFullJitterAndCapsAtFiveSeconds() {
        var policy = TupleWriter.randomizedExponentialRetryDelay(
            TupleWriter.DEFAULT_INITIAL_RETRY_DELAY,
            TupleWriter.DEFAULT_MAXIMUM_RETRY_DELAY,
            () -> 0.5
        );

        Assertions.assertEquals(
            Duration.ofMillis(50),
            policy.delayAfterFailure(1)
        );
        Assertions.assertEquals(
            Duration.ofMillis(100),
            policy.delayAfterFailure(2)
        );
        Assertions.assertEquals(
            Duration.ofMillis(1_600),
            policy.delayAfterFailure(6)
        );
        Assertions.assertEquals(
            Duration.ofMillis(2_500),
            policy.delayAfterFailure(7)
        );
        Assertions.assertEquals(
            Duration.ofMillis(2_500),
            policy.delayAfterFailure(40)
        );
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("TupleWriterLogger")
    void oneLogicalWriteOwnsPhysicalRetriesUntilDurable() {
        try (
            var telemetry = new InMemoryInstrumentationBundle(false, true);
            var logs = new TestLogAppender(TupleWriter.class)
        ) {
            var contexts = new ContextFixture();
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            var sink = new ScriptedSink();
            var fatalFailures = new ArrayList<Error>();
            var releases = new AtomicInteger();
            var writer = new TupleWriter<>(
                eventLoop,
                clock,
                Duration.ofSeconds(1),
                sink,
                ignored -> releases.incrementAndGet(),
                fatalFailures::add,
                new TupleDurabilityMetrics(
                    telemetry.openTelemetrySdk.getMeter("tuple-writer-behavior-test")
                )
            );

            var write = writer.write(
                new TupleWriter.WriteTuple<>(contexts.tuple, "tuple")
            );
            eventLoop.runUntilIdle();
            Assertions.assertEquals(1, sink.writes.get());

            sink.failNext();
            eventLoop.runUntilIdle();
            Assertions.assertFalse(write.completion().toCompletableFuture().isDone());

            eventLoop.advance(Duration.ofSeconds(1));
            Assertions.assertEquals(2, sink.writes.get());
            sink.failNext();
            eventLoop.runUntilIdle();
            Assertions.assertFalse(write.completion().toCompletableFuture().isDone());

            eventLoop.advance(Duration.ofSeconds(1));
            Assertions.assertEquals(3, sink.writes.get());
            sink.durableNext();
            eventLoop.runUntilIdle();

            Assertions.assertInstanceOf(
                TupleWriter.TupleDurable.class,
                write.completion().toCompletableFuture().join()
            );
            Assertions.assertEquals(1, releases.get());
            Assertions.assertEquals(0, writer.operations().activeCount());
            Assertions.assertTrue(fatalFailures.isEmpty());

            var recorded = telemetry.getFinishedMetrics();
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_ACCEPTED, 1);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_PENDING, 0);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.RETRIES, 2);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.TERMINAL_FAILURES, 0);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_DURABLE, 1);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_CANCELLED, 0);
            assertHistogram(
                recorded,
                TupleDurabilityMetrics.MetricNames.WRITE_LATENCY,
                1,
                2_000.0
            );
            Assertions.assertTrue(logs.contains(
                "tuple_durability_degraded",
                "request=",
                "state=retrying",
                "failed_attempts=1",
                "retry_delay_ms=1000"
            ));
            Assertions.assertEquals(
                1,
                logs.count("tuple_durability_degraded", Level.WARN),
                "one logical outage emits one WARN even when physical retries continue"
            );
            Assertions.assertTrue(logs.contains(
                "tuple_write_retried",
                "request=",
                "failed_attempts=2",
                "retry_delay_ms=1000"
            ));
            Assertions.assertTrue(logs.contains(
                "tuple_write_durable",
                "request=",
                "retries=2",
                "latency_ms=2000"
            ));
            Assertions.assertTrue(logs.contains(
                "tuple_durability_recovered",
                "request=",
                "retries=2",
                "latency_ms=2000"
            ));
            contexts.close();
        }
    }

    @Test
    void aCopyingSinkReleasesTheTupleAtWriteWhileDurabilityStillGatesCompletion() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var contexts = new ContextFixture();
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            var sink = new ScriptedSink();
            sink.copiesTupleOnWrite = true;
            var fatalFailures = new ArrayList<Error>();
            var releases = new AtomicInteger();
            var writer = new TupleWriter<>(
                eventLoop,
                clock,
                Duration.ofSeconds(1),
                sink,
                ignored -> releases.incrementAndGet(),
                fatalFailures::add,
                new TupleDurabilityMetrics(telemetry.openTelemetrySdk.getMeter("tuple-writer-hand-off-test"))
            );

            var write = writer.write(new TupleWriter.WriteTuple<>(contexts.tuple, "tuple"));
            eventLoop.runUntilIdle();

            Assertions.assertTrue(write.handedOff().toCompletableFuture().isDone(), "copied at write");
            Assertions.assertEquals(1, releases.get(), "the tuple is released before it is durable");
            Assertions.assertFalse(write.completion().toCompletableFuture().isDone(), "durability still pending");

            sink.durableNext();
            eventLoop.runUntilIdle();
            Assertions.assertInstanceOf(TupleWriter.TupleDurable.class, write.completion().toCompletableFuture().join());
            Assertions.assertEquals(1, releases.get(), "released exactly once");
            Assertions.assertTrue(fatalFailures.isEmpty());
            contexts.close();
        }
    }

    @Test
    void aFailureAfterHandOffIsFatalBecauseNothingRemainsToRewrite() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var contexts = new ContextFixture();
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            var sink = new ScriptedSink();
            sink.copiesTupleOnWrite = true;
            var fatalFailures = new ArrayList<Error>();
            var writer = new TupleWriter<>(
                eventLoop,
                clock,
                Duration.ofSeconds(1),
                sink,
                ignored -> {},
                fatalFailures::add,
                new TupleDurabilityMetrics(telemetry.openTelemetrySdk.getMeter("tuple-writer-hand-off-failure-test"))
            );

            var write = writer.write(new TupleWriter.WriteTuple<>(contexts.tuple, "tuple"));
            eventLoop.runUntilIdle();
            sink.failNext();
            eventLoop.runUntilIdle();
            eventLoop.advance(Duration.ofSeconds(5));

            Assertions.assertEquals(1, sink.writes.get(), "no retry once the sink owned the tuple");
            Assertions.assertEquals(1, fatalFailures.size());
            Assertions.assertTrue(write.completion().toCompletableFuture().isCompletedExceptionally());
            Assertions.assertEquals(0, writer.operations().activeCount());
            contexts.close();
        }
    }

    @Test
    void cancellationIsTypedAndLatePhysicalCompletionCannotBecomeDurability() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var contexts = new ContextFixture();
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            var sink = new ScriptedSink();
            var fatalFailures = new ArrayList<Error>();
            var releases = new AtomicInteger();
            var writer = new TupleWriter<>(
                eventLoop,
                clock,
                Duration.ofSeconds(1),
                sink,
                ignored -> releases.incrementAndGet(),
                fatalFailures::add,
                new TupleDurabilityMetrics(
                    telemetry.openTelemetrySdk.getMeter("tuple-cancellation-test")
                )
            );
            var write = writer.write(
                new TupleWriter.WriteTuple<>(contexts.tuple, "tuple")
            );
            eventLoop.runUntilIdle();

            var cancellation = new CancellationException("cancelled");
            write.cancel(cancellation);
            eventLoop.runUntilIdle();

            var cancelled = Assertions.assertInstanceOf(
                TupleWriter.TupleWriteCancelled.class,
                write.completion().toCompletableFuture().join()
            );
            Assertions.assertSame(cancellation, cancelled.cause());
            Assertions.assertEquals(1, releases.get());

            sink.durableNext();
            eventLoop.runUntilIdle();

            Assertions.assertInstanceOf(
                TupleWriter.TupleWriteCancelled.class,
                write.completion().toCompletableFuture().join()
            );
            Assertions.assertEquals(1, releases.get());
            Assertions.assertTrue(fatalFailures.isEmpty());

            var recorded = telemetry.getFinishedMetrics();
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_ACCEPTED, 1);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_PENDING, 0);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_CANCELLED, 1);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_DURABLE, 0);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.TERMINAL_FAILURES, 0);
            contexts.close();
        }
    }

    @Test
    void intentionalWholeTupleDropSkipsTheSinkAndCompletesDurably() {
        var contexts = new ContextFixture();
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var sink = new ScriptedSink();
        var transformations = new AtomicInteger();
        var releases = new AtomicInteger();
        var writer = new TupleWriter<>(
            eventLoop,
            clock,
            Duration.ZERO,
            (replayContext, tuple) -> {
                transformations.incrementAndGet();
                return new TupleWriter.TupleDropped<>();
            },
            sink,
            ignored -> releases.incrementAndGet(),
            failure -> Assertions.fail(failure),
            contexts.root.getTupleDurabilityMetrics()
        );

        var write = writer.write(
            new TupleWriter.WriteTuple<>(contexts.tuple, "candidate")
        );
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            TupleWriter.TupleDurable.class,
            write.completion().toCompletableFuture().join()
        );
        Assertions.assertEquals(1, transformations.get());
        Assertions.assertEquals(0, sink.writes.get());
        Assertions.assertEquals(1, releases.get());
        Assertions.assertEquals(0, writer.operations().activeCount());
        contexts.close();
    }

    @Test
    void graceFlushesOnlyTheAffectedGenerationUntilItsLastReferenceLeaves() {
        var contexts = new ContextFixture(REQUEST_ID);
        var otherRequestId = new ReplayRequestId(
            new ConnectionProcessingId(
                new PartitionGenerationId(new TopicPartition("traffic", 0), 2),
                new CapturedConnectionId("node", "other-connection"),
                1
            ),
            0
        );
        var otherContexts = new ContextFixture(otherRequestId);
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var sink = new ScriptedSink();
        var fatalFailures = new ArrayList<Error>();
        var writer = new TupleWriter<>(
            eventLoop,
            clock,
            Duration.ZERO,
            sink,
            ignored -> {},
            fatalFailures::add,
            contexts.root.getTupleDurabilityMetrics()
        );

        var generation = REQUEST_ID.connectionProcessingId().generation();
        writer.enterGrace(generation);
        eventLoop.runUntilIdle();
        Assertions.assertEquals(1, sink.flushes.get(), "grace entry must flush buffered tuples");
        writer.enterGrace(generation);
        eventLoop.runUntilIdle();
        Assertions.assertEquals(1, sink.flushes.get(), "another connection in the generation shares eager flush");

        var write = writer.write(new TupleWriter.WriteTuple<>(contexts.tuple, "tuple"));
        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, sink.writes.get());
        Assertions.assertEquals(
            2,
            sink.flushes.get(),
            "an accepted tuple must be flushed immediately while grace is active"
        );
        sink.durableNext();
        eventLoop.runUntilIdle();
        Assertions.assertInstanceOf(
            TupleWriter.TupleDurable.class,
            write.completion().toCompletableFuture().join()
        );

        var otherWrite = writer.write(
            new TupleWriter.WriteTuple<>(otherContexts.tuple, "other-generation")
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(
            2,
            sink.flushes.get(),
            "a generation outside grace must retain ordinary sink batching"
        );
        sink.durableNext();
        eventLoop.runUntilIdle();
        Assertions.assertInstanceOf(
            TupleWriter.TupleDurable.class,
            otherWrite.completion().toCompletableFuture().join()
        );

        writer.leaveGrace(generation);
        eventLoop.runUntilIdle();
        var stillInGrace = writer.write(
            new TupleWriter.WriteTuple<>(contexts.tuple, "last-generation-reference")
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(3, sink.flushes.get());
        sink.durableNext();
        eventLoop.runUntilIdle();
        Assertions.assertInstanceOf(
            TupleWriter.TupleDurable.class,
            stillInGrace.completion().toCompletableFuture().join()
        );

        writer.leaveGrace(generation);
        eventLoop.runUntilIdle();
        var afterGrace = writer.write(
            new TupleWriter.WriteTuple<>(contexts.tuple, "after-grace")
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(
            3,
            sink.flushes.get(),
            "the last connection leaving grace must restore ordinary batching"
        );
        sink.durableNext();
        eventLoop.runUntilIdle();
        Assertions.assertInstanceOf(
            TupleWriter.TupleDurable.class,
            afterGrace.completion().toCompletableFuture().join()
        );
        Assertions.assertTrue(fatalFailures.isEmpty());
        otherContexts.close();
        contexts.close();
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("TupleWriterLogger")
    void transformationFailureIsTerminalAndMetricObservable() {
        try (
            var telemetry = new InMemoryInstrumentationBundle(false, true);
            var logs = new TestLogAppender(TupleWriter.class)
        ) {
            var contexts = new ContextFixture();
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            var fatalFailures = new ArrayList<Error>();
            var writer = new TupleWriter<String>(
                eventLoop,
                clock,
                Duration.ZERO,
                (replayContext, tuple) -> {
                    throw new IllegalStateException("transform failed");
                },
                new ScriptedSink(),
                ignored -> {},
                fatalFailures::add,
                new TupleDurabilityMetrics(
                    telemetry.openTelemetrySdk.getMeter("tuple-transformation-failure-test")
                )
            );

            var write = writer.write(
                new TupleWriter.WriteTuple<>(contexts.tuple, "tuple")
            );
            eventLoop.runUntilIdle();

            Assertions.assertTrue(write.completion().toCompletableFuture().isCompletedExceptionally());
            Assertions.assertEquals(1, fatalFailures.size());
            var recorded = telemetry.getFinishedMetrics();
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_ACCEPTED, 1);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_PENDING, 0);
            assertMetric(
                recorded,
                TupleDurabilityMetrics.MetricNames.TERMINAL_FAILURES,
                Attributes.of(TupleDurabilityMetrics.FAILURE_REASON_ATTRIBUTE, "transformation"),
                1
            );
            Assertions.assertTrue(logs.contains(
                "tuple_durability_degraded",
                "request=",
                "state=terminal",
                "reason=transformation"
            ));
            contexts.close();
        }
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("TupleWriterLogger")
    void rejectedOwnerSubmissionIsTerminalAndMetricObservable() {
        try (
            var telemetry = new InMemoryInstrumentationBundle(false, true);
            var logs = new TestLogAppender(TupleWriter.class)
        ) {
            var contexts = new ContextFixture();
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            eventLoop.rejectNewTasks();
            var fatalFailures = new ArrayList<Error>();
            var writer = new TupleWriter<>(
                eventLoop,
                clock,
                Duration.ZERO,
                new ScriptedSink(),
                ignored -> {},
                fatalFailures::add,
                new TupleDurabilityMetrics(
                    telemetry.openTelemetrySdk.getMeter("tuple-owner-rejection-test")
                )
            );

            var write = writer.write(
                new TupleWriter.WriteTuple<>(contexts.tuple, "tuple")
            );

            Assertions.assertTrue(write.completion().toCompletableFuture().isCompletedExceptionally());
            Assertions.assertEquals(1, fatalFailures.size());
            var recorded = telemetry.getFinishedMetrics();
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_ACCEPTED, 1);
            assertMetric(recorded, TupleDurabilityMetrics.MetricNames.WRITES_PENDING, 0);
            assertMetric(
                recorded,
                TupleDurabilityMetrics.MetricNames.TERMINAL_FAILURES,
                Attributes.of(
                    TupleDurabilityMetrics.FAILURE_REASON_ATTRIBUTE,
                    "owner_submission_rejected"
                ),
                1
            );
            Assertions.assertTrue(logs.contains(
                "tuple_durability_degraded",
                "request=",
                "state=terminal",
                "reason=owner_submission_rejected"
            ));
            contexts.close();
        }
    }

    private static void assertMetric(
        Iterable<MetricData> metrics,
        String name,
        long expectedValue
    ) {
        assertMetric(metrics, name, Attributes.empty(), expectedValue);
    }

    private static void assertMetric(
        Iterable<MetricData> metrics,
        String name,
        Attributes attributes,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var value = metric.getLongSumData().getPoints().stream()
                    .filter(point -> point.getAttributes().equals(attributes))
                    .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                    .sum();
                Assertions.assertEquals(expectedValue, value);
                return;
            }
        }
        Assertions.assertEquals(0, expectedValue, "Missing metric " + name);
    }

    private static void assertHistogram(
        Iterable<MetricData> metrics,
        String name,
        long expectedCount,
        double expectedSum
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var point = metric.getHistogramData().getPoints().stream()
                    .findFirst()
                    .orElseThrow();
                Assertions.assertEquals(expectedCount, point.getCount());
                Assertions.assertEquals(expectedSum, point.getSum());
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private static final class ScriptedSink
        implements TupleWriter.PhysicalTupleSink<String> {

        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();
        private final Queue<CompletableFuture<Void>> completions = new ArrayDeque<>();
        private boolean copiesTupleOnWrite;

        @Override
        public boolean copiesTupleOnWrite() {
            return copiesTupleOnWrite;
        }

        @Override
        public CompletionStage<Void> write(
            IReplayContexts.ITupleHandlingContext replayContext,
            String tuple
        ) {
            writes.incrementAndGet();
            var completion = new CompletableFuture<Void>();
            completions.add(completion);
            return completion;
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        void failNext() {
            completions.remove().completeExceptionally(
                new IllegalStateException("sink unavailable")
            );
        }

        void durableNext() {
            completions.remove().complete(null);
        }
    }

    private static final class ContextFixture implements AutoCloseable {
        private final RootReplayerContext root =
            new RootReplayerContext(OpenTelemetry.noop());
        private final IReplayContexts.IKafkaRecordContext record;
        private final IReplayContexts.ITrafficStreamsLifecycleContext traffic;
        private final IReplayContexts.IRequestContext request;
        private final IReplayContexts.ITupleHandlingContext tuple;

        private ContextFixture() {
            this(REQUEST_ID);
        }

        private ContextFixture(ReplayRequestId requestId) {
            record =
            root.createKafkaRecordContext(
                new KafkaRecordId(requestId.connectionProcessingId().generation(), 0),
                0
            );
            traffic = record.createTrafficStreamContext(0);
            request =
            traffic.createRequestContext(
                requestId,
                java.time.Instant.EPOCH
            );
            request.onRequestReconstituted();
            tuple = request.createTupleContext();
        }

        @Override
        public void close() {
            tuple.close();
            request.close();
            traffic.close();
            record.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
        }
    }
}
