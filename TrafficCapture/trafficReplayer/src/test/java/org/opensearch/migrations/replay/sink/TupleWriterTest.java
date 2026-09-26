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
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.common.TopicPartition;
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
    void oneLogicalWriteOwnsPhysicalRetriesUntilDurable() {
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
            OutstandingOperationRegistry.CountHook.NOOP
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
        sink.durableNext();
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            TupleWriter.TupleDurable.class,
            write.completion().toCompletableFuture().join()
        );
        Assertions.assertEquals(1, releases.get());
        Assertions.assertEquals(0, writer.operations().activeCount());
        Assertions.assertTrue(fatalFailures.isEmpty());
        contexts.close();
    }

    @Test
    void cancellationIsTypedAndLatePhysicalCompletionCannotBecomeDurability() {
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
            OutstandingOperationRegistry.CountHook.NOOP
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
        contexts.close();
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
            OutstandingOperationRegistry.CountHook.NOOP
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
    void graceFlushesOnEntryAndImmediatelyAfterEachAcceptedTuple() {
        var contexts = new ContextFixture();
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
            OutstandingOperationRegistry.CountHook.NOOP
        );

        writer.enterGrace();
        eventLoop.runUntilIdle();
        Assertions.assertEquals(1, sink.flushes.get(), "grace entry must flush buffered tuples");
        writer.enterGrace();
        eventLoop.runUntilIdle();
        Assertions.assertEquals(1, sink.flushes.get(), "duplicate grace entry must not flush again");

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
        Assertions.assertTrue(fatalFailures.isEmpty());
        contexts.close();
    }

    private static final class ScriptedSink
        implements TupleWriter.PhysicalTupleSink<String> {

        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();
        private final Queue<CompletableFuture<Void>> completions = new ArrayDeque<>();

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
        private final IReplayContexts.IKafkaRecordContext record =
            root.createKafkaRecordContext(
                new KafkaRecordId(REQUEST_ID.connectionProcessingId().generation(), 0),
                0
            );
        private final IReplayContexts.ITrafficStreamsLifecycleContext traffic =
            record.createTrafficStreamContext(0);
        private final IReplayContexts.IRequestContext request =
            traffic.createRequestContext(
                REQUEST_ID,
                java.time.Instant.EPOCH
            );
        private final IReplayContexts.ITupleHandlingContext tuple;

        private ContextFixture() {
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
