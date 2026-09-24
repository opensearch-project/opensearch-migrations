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
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.WriterPartitionId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput.PartitionRecordBatch;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.tracing.KafkaSourceRootContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.PolledKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput.RecordProcessingFinished;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput.RequestNextPartitionBatch;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

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

    /**
     * G0's exit evidence, restated against the owner that now drives the fixture: a mixed traffic, heartbeat
     * and probe batch reaches replay intake with its exact broker timestamps, and the pause, resume, poll and
     * commit transitions are observable in order.
     */
    @Test
    void mixedScriptPumpsThroughWithExactBrokerTimestampsAndObservableTransitions() throws Exception {
        var traffic = TrafficStream.newBuilder().setConnectionId("connection-1").setNumber(0).build();
        var script = new RecordScript("traffic", 7)
            .addTraffic(4, 100, Instant.ofEpochMilli(1_700_000_000_000L), "writer-a", traffic, "request-1")
            .addHeartbeat(4, 101, Instant.ofEpochMilli(1_700_000_005_000L), "writer-a", 5_000)
            .addProbe(4, 102, Instant.ofEpochMilli(1_700_000_010_000L), "writer-a", "probe-1");
        var topicPartition = script.generation(4).topicPartition();

        var telemetry = new InMemoryInstrumentationBundle(false, false);
        try {
            var port = new PumpedKafkaSource(List.of(topicPartition));
            // REBUILD-LIMBO-NOTE(G3): becomes RootReplayerContext.
            var rootContext = new KafkaSourceRootContext(telemetry.openTelemetrySdk);
            // A counting action, not a no-op: with a no-op this test cannot distinguish zero wakeups from
            // several, which is exactly the observability G0 claims.
            var wakeupsIssued = new java.util.concurrent.atomic.AtomicInteger();
            var wakeupController = new WakeupController(wakeupsIssued::incrementAndGet, rootContext);
            var sourceInputs = new KafkaSourceInputQueue(wakeupController);
            var intakeInputs = new ReplayIntakeInputQueue();
            var owner = new KafkaSourceOwner(
                port,
                sourceInputs,
                intakeInputs,
                wakeupController,
                Duration.ofSeconds(1),
                () -> 0L,
                // Non-blocking: this self-test never revokes, and a real wait against a frozen clock would
                // be the two-clock problem kafkaLLD 15.1 forbids.
                deadline -> !sourceInputs.isEmpty()
            );

            // Assignment arrives from inside a poll, which is where Kafka delivers rebalance callbacks.
            port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsAssigned(List.of(topicPartition)));
            owner.runOnce();
            var generation = owner.partitionState(topicPartition).orElseThrow().generation();
            var requestId = new PartitionBatchRequestId(generation, 1);
            sourceInputs.submit(new RequestNextPartitionBatch(requestId));
            port.scriptPoll(Map.of(topicPartition, asPolled(script)));
            port.clearHistory();

            owner.runOnce();

            Assertions.assertEquals(
                List.of("resume(traffic-4)", "poll->[traffic-4]", "pause(traffic-4)"),
                port.history(),
                "the partition must be resumed to read, then paused before its batch is applied"
            );

            // Assignment queued a PartitionGenerationAssigned first, so select the batch rather than
            // assuming it is at the head.
            PartitionRecordBatch batch = null;
            while (intakeInputs.size() > 0) {
                var queued = intakeInputs.take();
                if (queued instanceof PartitionRecordBatch delivered) {
                    batch = delivered;
                }
            }
            Assertions.assertNotNull(batch, "no PartitionRecordBatch reached intake");
            Assertions.assertEquals(requestId, batch.requestId());
            Assertions.assertEquals(
                List.of(1_700_000_000_000L, 1_700_000_005_000L, 1_700_000_010_000L),
                batch.records().stream().map(ApplicationKafkaRecord::logAppendTimeMillis).toList(),
                "broker timestamps must survive the trip to intake exactly"
            );
            Assertions.assertEquals(
                List.of(
                    CaptureRecord.PayloadCase.TRAFFICSTREAM,
                    CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT,
                    CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE
                ),
                batch.records().stream().map(r -> r.envelope().getPayloadCase()).toList(),
                "every envelope case must arrive, in order"
            );

            // Finishing every record commits the contiguous prefix once, at the last offset plus one.
            batch.records().forEach(r -> sourceInputs.submit(new RecordProcessingFinished(r.recordId())));
            port.clearHistory();
            owner.runOnce();

            Assertions.assertEquals(
                List.of("commitAsync{traffic-4=103}"),
                port.history().stream().filter(call -> call.startsWith("commit")).toList(),
                () -> "one contiguous commit expected, submitted asynchronously because the ordinary loop must"
                    + " not block on it; history: " + port.history()
            );
            // G0 asks for observable wakeup, which the milestone previously claimed with a no-op action that
            // could not tell zero wakeups from several. Counting them is what makes the claim checkable — and
            // the count here is zero on purpose: every submission above happened between iterations, with the
            // Kafka thread not in poll(), and kafkaLLD §5.4 issues no wakeup then because the loop reaches the
            // queue by itself. Asserting zero is only worth anything alongside the case below, which proves
            // the same counter does reach one.
            Assertions.assertEquals(
                0,
                wakeupsIssued.get(),
                () -> "a submission made between loop iterations needs no wakeup, but " + wakeupsIssued.get()
                    + " were issued"
            );

            // Submitted from inside poll(), which is the only phase where a wakeup is the sole way to shorten
            // the wait. One submission, one wakeup.
            port.scriptRebalanceDuringNextPoll(() ->
                sourceInputs.submit(new RecordProcessingFinished(
                    new org.opensearch.migrations.replay.identity.KafkaRecordId(generation, 999)))
            );
            owner.runOnce();

            Assertions.assertEquals(
                1,
                wakeupsIssued.get(),
                "a submission while the Kafka thread may be waiting in poll() must issue exactly one wakeup"
            );
        } finally {
            telemetry.close();
        }
    }

    /**
     * Strips the script's records back to what a port actually returns.
     *
     * <p>This used to restamp them onto the generation the owner allocated, because the port carried a
     * generation map and had to agree with the owner. The owner stamps now, so there is nothing to keep in
     * step — a port that cannot know a generation cannot disagree about one.
     */
    private static List<PolledKafkaRecord> asPolled(RecordScript script) {
        return script.records()
            .stream()
            .map(record -> new PolledKafkaRecord(
                record.recordId().offset(),
                record.logAppendTimeMillis(),
                record.serializedSizeBytes(),
                record.envelope()
            ))
            .toList();
    }
}
