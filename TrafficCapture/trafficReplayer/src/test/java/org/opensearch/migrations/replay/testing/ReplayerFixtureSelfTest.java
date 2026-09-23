/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.testing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.WriterPartitionId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput.PartitionRecordBatch;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.BatchDelivered;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.CommitSubmitted;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.DriverPort;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionPaused;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionResumed;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.WakeupRequested;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput.CaptureProtocolViolationDetected;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput.GenerationCleanupFinished;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput.RecordProcessingFinished;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput.RequestNextPartitionBatch;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.channel.local.LocalChannel;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayerFixtureSelfTest {
    @Test
    void fakeClockSharesTimeAcrossZoneViewsAndRejectsRegression() {
        var clock = new FakeClock(Instant.ofEpochSecond(10));
        var easternView = clock.withZone(ZoneId.of("America/New_York"));

        clock.advance(Duration.ofMillis(250));

        Assertions.assertEquals(Instant.ofEpochSecond(10, 250_000_000), clock.instant());
        Assertions.assertEquals(clock.instant(), easternView.instant());
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> clock.set(Instant.ofEpochSecond(9))
        );
    }

    @Test
    void testEventLoopRunsTasksAndEqualDeadlineTimersDeterministically() {
        var eventLoop = new TestEventLoop();
        var events = new ArrayList<String>();
        eventLoop.execute(() -> events.add("task-1"));
        eventLoop.execute(() -> events.add("task-2"));
        eventLoop.schedule(() -> events.add("timer-1"), Duration.ofSeconds(2));
        eventLoop.schedule(() -> events.add("timer-2"), Duration.ofSeconds(2));

        eventLoop.runNext();
        Assertions.assertEquals(List.of("task-1"), events);
        eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("task-1", "task-2"), events);

        eventLoop.advance(Duration.ofSeconds(2));

        Assertions.assertEquals(List.of("task-1", "task-2", "timer-1", "timer-2"), events);
        Assertions.assertEquals(0, eventLoop.pendingTimers());
    }

    /**
     * Guards the choice not to extend Netty's {@code AbstractScheduledEventExecutor}, whose scheduler
     * measures deadlines against {@code System.nanoTime()}: timers would fire on wall clock and a test
     * could not hold time still.
     */
    @Test
    void testEventLoopTimersFollowTheInjectedClockRatherThanWallClock() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var events = new ArrayList<String>();
        eventLoop.schedule(() -> events.add("netty-contract-timer"), 1, TimeUnit.MINUTES);

        eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of(), events);
        Assertions.assertEquals(1, eventLoop.pendingTimers());

        eventLoop.advance(Duration.ofMinutes(1));

        Assertions.assertEquals(List.of("netty-contract-timer"), events);
    }

    /**
     * A real event loop never leaves a deadline that has already passed pending, so neither does
     * this one: a timer scheduled by a timer running inside {@link TestEventLoop#advance} is due
     * immediately and runs in the same call.
     */
    @Test
    void testEventLoopAdvanceSettlesTimersScheduledWhileItRuns() {
        var eventLoop = new TestEventLoop();
        var events = new ArrayList<String>();
        eventLoop.schedule(() -> {
            events.add("outer");
            eventLoop.schedule(() -> events.add("inner"), Duration.ZERO);
        }, Duration.ofSeconds(1));

        eventLoop.advance(Duration.ofSeconds(1));

        Assertions.assertEquals(List.of("outer", "inner"), events);
        Assertions.assertEquals(0, eventLoop.pendingTimers());
        Assertions.assertEquals(0, eventLoop.pendingTasks());
    }

    @Test
    void testEventLoopCancelledTimerLeavesNoPendingWork() {
        var eventLoop = new TestEventLoop();
        var events = new ArrayList<String>();
        var timer = eventLoop.schedule(() -> events.add("cancelled"), Duration.ofSeconds(1));

        Assertions.assertTrue(timer.cancel(false));
        Assertions.assertTrue(timer.isCancelled());
        Assertions.assertEquals(0, eventLoop.pendingTimers());

        eventLoop.advance(Duration.ofSeconds(5));

        Assertions.assertEquals(List.of(), events);
        Assertions.assertFalse(timer.cancel(false));
    }

    /**
     * Owner affinity is asserted with {@code inEventLoop()}, so the fixture must report membership
     * only while it is running a submitted task. Otherwise a test could mutate owner state directly
     * and still satisfy the assertion that exists to forbid exactly that.
     */
    @Test
    void testEventLoopReportsAffinityOnlyWhileRunningATask() {
        var eventLoop = new TestEventLoop();
        var affinityInsideTask = new ArrayList<Boolean>();

        Assertions.assertFalse(eventLoop.inEventLoop());
        eventLoop.execute(() -> affinityInsideTask.add(eventLoop.inEventLoop()));
        Assertions.assertFalse(eventLoop.inEventLoop());

        eventLoop.runUntilIdle();

        Assertions.assertEquals(List.of(true), affinityInsideTask);
        Assertions.assertFalse(eventLoop.inEventLoop());
    }

    /**
     * Netty's built-in channels gate registration on their own loop implementation, so none can register
     * against a custom event loop. Asserts that {@code register} <strong>throws</strong> rather than
     * failing a promise the caller might not inspect, and that the message names the alternative, since
     * whoever trips over this is reading the exception rather than this test.
     */
    @Test
    void testEventLoopRefusesToHoldChannelsWithAMessageNamingTheAlternative() {
        var eventLoop = new TestEventLoop();
        var channel = new LocalChannel();

        var thrown = Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> eventLoop.register(channel)
        );

        Assertions.assertTrue(thrown.getMessage().contains("TargetChannelPort"));
        Assertions.assertTrue(thrown.getMessage().contains("NioEventLoopGroup"));
        Assertions.assertFalse(channel.isRegistered());
        Assertions.assertEquals(0, eventLoop.pendingTasks());
    }

    /**
     * The fatal owner-loss path needs a loop that can die. Rejection and dropped accepted work are
     * the two shapes of that, per {@code replayerConnectionAndRequestLowLevelDesign.md} 19.6.
     */
    @Test
    void testEventLoopTerminationRejectsNewWorkAndDropsAcceptedWork() {
        var eventLoop = new TestEventLoop();
        var events = new ArrayList<String>();
        eventLoop.execute(() -> events.add("accepted-but-never-run"));
        eventLoop.schedule(() -> events.add("accepted-timer"), Duration.ofSeconds(1));

        eventLoop.shutdown();

        Assertions.assertTrue(eventLoop.isShutdown());
        Assertions.assertTrue(eventLoop.isShuttingDown());
        Assertions.assertFalse(eventLoop.isTerminated());
        Assertions.assertThrows(
            RejectedExecutionException.class,
            () -> eventLoop.execute(() -> events.add("rejected"))
        );
        Assertions.assertThrows(
            RejectedExecutionException.class,
            () -> eventLoop.schedule(() -> events.add("rejected-timer"), Duration.ofSeconds(1))
        );

        eventLoop.dropAcceptedWork();

        Assertions.assertTrue(eventLoop.isTerminated());
        Assertions.assertTrue(eventLoop.terminationFuture().isSuccess());
        Assertions.assertEquals(List.of(), events);
    }

    @Test
    void recordScriptPreservesEnvelopeCasesMetadataAndIndependentAssociationOracle() {
        var traffic = TrafficStream.newBuilder()
            .setConnectionId("connection")
            .setNumber(0)
            .build();
        var script = new RecordScript("traffic")
            .addTraffic(
                2,
                10,
                Instant.ofEpochMilli(1_000),
                "writer-1",
                traffic,
                "request-7",
                "request-8"
            )
            .addHeartbeat(2, 11, Instant.ofEpochMilli(2_000), "writer-1", 10_000)
            .addProbe(2, 12, Instant.ofEpochMilli(3_000), "activation:PROBE", "probe-1");

        var trafficRecord = script.next();
        var heartbeatRecord = script.next();
        var probeRecord = script.next();

        Assertions.assertEquals(script.generation(2), trafficRecord.recordId().generation());
        Assertions.assertEquals(10, trafficRecord.recordId().offset());
        Assertions.assertEquals(1_000, trafficRecord.logAppendTimeMillis());
        Assertions.assertEquals(
            new WriterPartitionId("writer-1", script.generation(2).topicPartition()),
            script.writerOf(trafficRecord.recordId())
        );
        Assertions.assertEquals(
            CaptureRecord.PayloadCase.TRAFFICSTREAM,
            trafficRecord.envelope().getPayloadCase()
        );
        Assertions.assertEquals("writer-1", trafficRecord.envelope().getTrafficStream().getNodeId());
        Assertions.assertEquals(
            CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT,
            heartbeatRecord.envelope().getPayloadCase()
        );
        Assertions.assertEquals(
            CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE,
            probeRecord.envelope().getPayloadCase()
        );
        script.assertAssociations(trafficRecord.recordId(), List.of("request-8", "request-7"));
        var mismatch = Assertions.assertThrows(
            AssertionError.class,
            () -> script.assertAssociations(trafficRecord.recordId(), List.of("request-7"))
        );
        Assertions.assertTrue(mismatch.getMessage().contains("request-8"));
        script.assertExhausted();
        Assertions.assertThrows(AssertionError.class, script::next);
    }

    /**
     * The broker timestamp is the reason {@code ApplicationKafkaRecord} exists, so the script must
     * carry the value the test wrote rather than any clock reading, and the serialized size must be
     * the envelope's own.
     */
    @Test
    void recordScriptCarriesExactBrokerTimestampsAndSerializedSizes() {
        var script = new RecordScript("traffic", 3)
            .addHeartbeat(1, 21, Instant.ofEpochMilli(10_000), "writer", 10_000)
            .addProbe(1, 22, Instant.ofEpochMilli(11_000), "writer", "probe")
            .addPayloadNotSet(1, 23, Instant.ofEpochMilli(12_000), "writer");

        var records = script.records();

        Assertions.assertEquals(
            List.of(10_000L, 11_000L, 12_000L),
            records.stream().map(record -> record.logAppendTimeMillis()).toList()
        );
        Assertions.assertEquals(
            List.of(21L, 22L, 23L),
            records.stream().map(record -> record.recordId().offset()).toList()
        );
        records.forEach(record -> Assertions.assertEquals(
            record.envelope().getSerializedSize(),
            record.serializedSizeBytes()
        ));
        Assertions.assertEquals(
            CaptureRecord.PayloadCase.PAYLOAD_NOT_SET,
            records.get(2).envelope().getPayloadCase()
        );
        Assertions.assertEquals(
            new WriterPartitionId("writer", script.generation(1).topicPartition()),
            script.writerOf(records.get(2).recordId())
        );
    }

    @Test
    void pumpedSourceObservesCoalescedWakeupPauseResumeDeliveryAndCommit() {
        var script = new RecordScript("traffic", 3)
            .addHeartbeat(1, 21, Instant.ofEpochMilli(10_000), "writer", 10_000)
            .addProbe(1, 22, Instant.ofEpochMilli(11_000), "activation:PROBE", "probe");
        var generation = script.generation(1);
        var requestId = new PartitionBatchRequestId(generation, 9);
        var outstandingRequest = new AtomicReference<PartitionBatchRequestId>();

        PumpedKafkaSource.SourceOwnerDriver driver = port -> {
            drainInputs(port, outstandingRequest);
            if (outstandingRequest.get() != null) {
                port.pollKafka().ifPresent(records -> {
                    port.pause(generation.topicPartition(), PumpedKafkaSource.PauseReason.BATCH_DEMAND);
                    port.deliver(new PartitionRecordBatch(outstandingRequest.getAndSet(null), records));
                });
            }
        };
        var source = new PumpedKafkaSource(driver);
        source.scriptBatch(script.records());
        source.submit(new RequestNextPartitionBatch(requestId));
        source.submit(new GenerationCleanupFinished(generation));

        source.runOnce();

        var firstPass = source.observations();
        Assertions.assertEquals(1, firstPass.stream().filter(WakeupRequested.class::isInstance).count());
        Assertions.assertEquals(1, firstPass.stream().filter(PartitionResumed.class::isInstance).count());
        Assertions.assertEquals(1, firstPass.stream().filter(PartitionPaused.class::isInstance).count());
        var delivered = firstPass.stream()
            .filter(BatchDelivered.class::isInstance)
            .map(BatchDelivered.class::cast)
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(requestId, delivered.batch().requestId());
        Assertions.assertEquals(script.records(), delivered.batch().records());

        source.submit(new RecordProcessingFinished(script.records().get(0).recordId()));
        source.runOnce();

        var commit = source.observations()
            .stream()
            .filter(CommitSubmitted.class::isInstance)
            .map(CommitSubmitted.class::cast)
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(Map.of(generation.topicPartition(), 22L), commit.nextOffsets());
        source.assertExhausted();
    }

    @Test
    void pumpedSourceReportsDriverFailureAndRefusesFurtherUse() {
        var failure = new IllegalStateException("injected owner failure");
        var source = new PumpedKafkaSource(port -> {
            throw failure;
        });

        var thrown = Assertions.assertThrows(AssertionError.class, source::runOnce);

        Assertions.assertSame(failure, thrown.getCause());
        Assertions.assertInstanceOf(
            PumpedKafkaSource.DriverFailed.class,
            source.observations().get(0)
        );
        Assertions.assertThrows(AssertionError.class, source::runOnce);
    }

    /**
     * G0's exit evidence, in one deterministic transition history: a mixed traffic, heartbeat and
     * probe script pumps through the source with its exact broker timestamps intact, and the pause,
     * wakeup, delivery and commit transitions are all observable in order.
     *
     * <p>Two properties are asserted by position in that history rather than by count. The partition
     * is paused <em>before</em> its batch is delivered ({@code kafkaLLD §17.4}). And three record
     * completions submitted back to back produce one wakeup, not three, which is the coalescing the
     * same section requires.
     *
     * <p>The commit is a single contiguous-prefix commit computed after every input is drained,
     * because commit authority belongs to the source alone — no record, request or retry policy may
     * commit on its own ({@code kafkaLLD §4.2}).
     */
    @Test
    void mixedScriptPumpsThroughWithExactBrokerTimestampsAndObservableTransitions() {
        var traffic = TrafficStream.newBuilder().setConnectionId("connection-1").setNumber(0).build();
        var script = new RecordScript("traffic", 7)
            .addTraffic(4, 100, Instant.ofEpochMilli(1_700_000_000_000L), "writer-a", traffic, "request-1")
            .addHeartbeat(4, 101, Instant.ofEpochMilli(1_700_000_005_000L), "writer-a", 5_000)
            .addProbe(4, 102, Instant.ofEpochMilli(1_700_000_010_000L), "writer-a", "probe-1");
        var generation = script.generation(4);
        var topicPartition = generation.topicPartition();
        var requestId = new PartitionBatchRequestId(generation, 1);
        var outstandingRequest = new AtomicReference<PartitionBatchRequestId>();
        var finishedOffsets = new TreeSet<Long>();
        var committedThrough = new AtomicReference<Long>(99L);

        var source = new PumpedKafkaSource(port -> {
            for (var input = port.pollInput(); input.isPresent(); input = port.pollInput()) {
                switch (input.get()) {
                    case RequestNextPartitionBatch request -> {
                        Assertions.assertNull(outstandingRequest.getAndSet(request.requestId()));
                        port.resume(topicPartition);
                    }
                    case RecordProcessingFinished finished ->
                        finishedOffsets.add(finished.recordId().offset());
                    case GenerationCleanupFinished ignored -> {}
                    case CaptureProtocolViolationDetected ignored -> {}
                }
            }
            if (outstandingRequest.get() != null) {
                port.pollKafka().ifPresent(records -> {
                    port.pause(topicPartition, PumpedKafkaSource.PauseReason.BATCH_DEMAND);
                    port.deliver(new PartitionRecordBatch(outstandingRequest.getAndSet(null), records));
                });
            }
            var nextOffset = committedThrough.get() + 1;
            while (finishedOffsets.remove(nextOffset)) {
                nextOffset++;
            }
            if (nextOffset > committedThrough.get() + 1) {
                committedThrough.set(nextOffset - 1);
                port.submitCommit(Map.of(topicPartition, nextOffset));
            }
        });

        source.scriptBatch(script.records());
        source.submit(new RequestNextPartitionBatch(requestId));
        source.runOnce();

        var delivered = source.observations()
            .stream()
            .filter(BatchDelivered.class::isInstance)
            .map(BatchDelivered.class::cast)
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(
            List.of(1_700_000_000_000L, 1_700_000_005_000L, 1_700_000_010_000L),
            delivered.batch().records().stream().map(ApplicationKafkaRecord::logAppendTimeMillis).toList()
        );
        Assertions.assertEquals(
            List.of(
                CaptureRecord.PayloadCase.TRAFFICSTREAM,
                CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT,
                CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE
            ),
            delivered.batch().records().stream().map(r -> r.envelope().getPayloadCase()).toList()
        );

        script.records().forEach(record -> source.submit(new RecordProcessingFinished(record.recordId())));
        source.runOnce();

        Assertions.assertEquals(
            List.of(
                "WakeupRequested",
                "PartitionResumed(traffic-4)",
                "PartitionPaused(traffic-4,BATCH_DEMAND)",
                "BatchDelivered(traffic-4#7.batch1,3 records)",
                "WakeupRequested",
                "CommitSubmitted(traffic-4=103)"
            ),
            source.observations().stream().map(ReplayerFixtureSelfTest::describe).toList()
        );
        source.assertExhausted();
    }

    private static String describe(PumpedKafkaSource.Observation observation) {
        return switch (observation) {
            case WakeupRequested ignored -> "WakeupRequested";
            case PartitionResumed resumed -> "PartitionResumed(" + resumed.topicPartition() + ")";
            case PartitionPaused paused ->
                "PartitionPaused(" + paused.topicPartition() + "," + paused.reason() + ")";
            case BatchDelivered delivered -> "BatchDelivered("
                + delivered.batch().requestId()
                + ","
                + delivered.batch().records().size()
                + " records)";
            case CommitSubmitted commit -> commit.nextOffsets()
                .entrySet()
                .stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(",", "CommitSubmitted(", ")"));
            case PumpedKafkaSource.DriverFailed failed ->
                "DriverFailed(" + failed.failure().getMessage() + ")";
        };
    }

    private static void drainInputs(
        DriverPort port,
        AtomicReference<PartitionBatchRequestId> outstandingRequest
    ) {
        for (var input = port.pollInput(); input.isPresent(); input = port.pollInput()) {
            switch (input.get()) {
                case RequestNextPartitionBatch request -> {
                    Assertions.assertNull(outstandingRequest.getAndSet(request.requestId()));
                    port.resume(request.requestId().generation().topicPartition());
                }
                case RecordProcessingFinished finished -> {
                    var offsets = new LinkedHashMap<TopicPartition, Long>();
                    offsets.put(
                        finished.recordId().generation().topicPartition(),
                        finished.recordId().offset() + 1
                    );
                    port.submitCommit(offsets);
                }
                case GenerationCleanupFinished ignored -> {}
                case CaptureProtocolViolationDetected ignored -> {}
            }
        }
    }
}
