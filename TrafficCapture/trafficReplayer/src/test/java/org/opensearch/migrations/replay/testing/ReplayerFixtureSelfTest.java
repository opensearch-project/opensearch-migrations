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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

// REBUILD-LIMBO-START(G11)
/*
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.kafka.PumpedKafkaSource;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.BatchDelivered;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.CommitSubmitted;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.DriverPort;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionBatchRequestId;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionGenerationId;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionPaused;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionRecordBatch;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.PartitionResumed;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.RecordProcessingFinished;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.RequestNextPartitionBatch;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource.WakeupRequested;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import org.apache.kafka.common.TopicPartition;
*/
// REBUILD-LIMBO-END(G11)

import io.netty.channel.local.LocalChannel;
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
     * The reason this fixture does not extend Netty's {@code AbstractScheduledEventExecutor}: that
     * scheduler measures deadlines against {@code System.nanoTime()}, so timers would fire on wall
     * clock instead of on the injected clock, and the test could not hold time still.
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
     * Records a constraint rather than a behavior we want. Netty's built-in channels gate
     * registration on their own loop implementation -- {@code LocalChannel} requires
     * {@code SingleThreadEventLoop} and {@code AbstractNioChannel} requires {@code NioEventLoop} --
     * so no real channel can register against any custom event loop, this one included. Becoming a
     * {@code SingleThreadEventLoop} would reintroduce both the wall-clock scheduler and a real
     * thread, so the fixture accepts the limit instead. Nothing needs the combination: target I/O
     * reaches owners through {@code TargetChannelPort}, not through a channel a test registered to
     * the owner's loop. This test exists so that limit is discovered here and not in a later
     * milestone, where it surfaces as an opaque "incompatible event loop type".
     */
    @Test
    void testEventLoopCannotRegisterNettyBuiltInChannels() {
        var eventLoop = new TestEventLoop();

        var registration = eventLoop.register(new LocalChannel());

        Assertions.assertInstanceOf(IllegalStateException.class, registration.cause());
        Assertions.assertTrue(registration.cause().getMessage().contains("incompatible event loop type"));
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

// REBUILD-LIMBO-START(G11)
/*
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

        Assertions.assertEquals(new TopicPartition("traffic", 2), trafficRecord.id().topicPartition());
        Assertions.assertEquals(10, trafficRecord.id().offset());
        Assertions.assertEquals(1_000, trafficRecord.logAppendTimeMillis());
        Assertions.assertEquals("writer-1", trafficRecord.writerNodeId());
        Assertions.assertEquals(CaptureRecord.PayloadCase.TRAFFICSTREAM, trafficRecord.payloadCase());
        Assertions.assertEquals("writer-1", trafficRecord.envelope().getTrafficStream().getNodeId());
        Assertions.assertEquals(
            CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT,
            heartbeatRecord.payloadCase()
        );
        Assertions.assertEquals(
            CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE,
            probeRecord.payloadCase()
        );
        script.assertAssociations(trafficRecord.id(), List.of("request-8", "request-7"));
        var mismatch = Assertions.assertThrows(
            AssertionError.class,
            () -> script.assertAssociations(trafficRecord.id(), List.of("request-7"))
        );
        Assertions.assertTrue(mismatch.getMessage().contains("request-8"));
        script.assertExhausted();
        Assertions.assertThrows(AssertionError.class, script::next);
    }

    @Test
    void pumpedSourceObservesCoalescedWakeupPauseResumeDeliveryAndCommit() {
        var generation = new PartitionGenerationId(new TopicPartition("traffic", 1), 3);
        var requestId = new PartitionBatchRequestId(generation, 9);
        var script = new RecordScript("traffic")
            .addHeartbeat(1, 21, Instant.ofEpochMilli(10_000), "writer", 10_000)
            .addProbe(1, 22, Instant.ofEpochMilli(11_000), "activation:PROBE", "probe");
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
        source.submit(new PumpedKafkaSource.GenerationCleanupFinished(generation));

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

        source.submit(new RecordProcessingFinished(script.records().get(0).id()));
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
                        finished.recordId().topicPartition(),
                        finished.recordId().offset() + 1
                    );
                    port.submitCommit(offsets);
                }
                case PumpedKafkaSource.GenerationCleanupFinished ignored -> {}
                case PumpedKafkaSource.CaptureProtocolViolationDetected ignored -> {}
            }
        }
    }
*/
// REBUILD-LIMBO-END(G11)
}
