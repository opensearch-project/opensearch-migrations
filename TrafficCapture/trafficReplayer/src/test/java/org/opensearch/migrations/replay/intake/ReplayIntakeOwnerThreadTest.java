/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.ReplayIntakeMetrics;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Evidence for the complete G3 production chain: producer, queue, owner thread, consumer, observability,
 * construction, and FIFO stop fence.
 */
class ReplayIntakeOwnerThreadTest {

    @Test
    void assignmentBootstrapAndExplicitBatchStayOrderedAndRequestOnlyAfterFullApplication() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var sourceInputs = new KafkaSourceInputQueue(new WakeupController(() -> {}, rootContext));
            var observedOffsets = new ArrayList<Long>();
            var emittedWhileExplicitBatchWasApplying = new ArrayList<KafkaSourceInput>();
            var owner = new ReplayIntakeOwner(
                new ReplayIntakeInputQueue(),
                sourceInputs,
                new RecordingSink(() -> true, new CountDownLatch(0), new CountDownLatch(0)),
                failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
                rootContext.replayIntakeMetrics,
                record -> {
                    observedOffsets.add(record.recordId().offset());
                    if (record.recordId().offset() >= 2) {
                        emittedWhileExplicitBatchWasApplying.addAll(sourceInputs.drain());
                    }
                },
                new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
                2
            );
            var script = new RecordScript("traffic")
                .addHeartbeat(0, 0, Instant.ofEpochMilli(1_000), "writer", 100)
                .addHeartbeat(0, 1, Instant.ofEpochMilli(1_001), "writer", 100)
                .addHeartbeat(0, 2, Instant.ofEpochMilli(1_002), "writer", 100)
                .addHeartbeat(0, 3, Instant.ofEpochMilli(1_003), "writer", 100);
            var generation = script.generation(0);

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));

            var assignmentOutputs = sourceInputs.drain();
            Assertions.assertEquals(1, assignmentOutputs.size());
            var explicitOne = Assertions.assertInstanceOf(
                KafkaSourceInput.RequestNextPartitionBatch.class,
                assignmentOutputs.get(0)
            ).requestId();
            Assertions.assertEquals(new PartitionBatchRequestId(generation, 1), explicitOne);
            var state = owner.partitionState(generation).orElseThrow();
            Assertions.assertEquals(
                PartitionIntakeState.BootstrapBatchState.PENDING,
                state.bootstrapBatchState()
            );
            Assertions.assertEquals(
                PartitionIntakeState.RequestedBatchState.REQUESTED,
                state.requestedBatchState(),
                "assignment may install one explicit request while bootstrap remains pending"
            );

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                new PartitionBatchRequestId(generation, 0),
                script.records().subList(0, 2)
            ));
            Assertions.assertEquals(
                PartitionIntakeState.BootstrapBatchState.CONSUMED,
                state.bootstrapBatchState()
            );
            Assertions.assertEquals(
                PartitionIntakeState.RequestedBatchState.REQUESTED,
                state.requestedBatchState(),
                "bootstrap must not resolve the overlapping explicit entitlement"
            );
            Assertions.assertTrue(
                sourceInputs.drain().stream()
                    .noneMatch(KafkaSourceInput.RequestNextPartitionBatch.class::isInstance),
                "applying bootstrap must not create a second explicit request"
            );

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                explicitOne,
                script.records().subList(2, 4)
            ));

            Assertions.assertTrue(
                emittedWhileExplicitBatchWasApplying.stream()
                    .noneMatch(KafkaSourceInput.RequestNextPartitionBatch.class::isInstance),
                "the next request must not be issued while any record in the current batch remains unapplied"
            );
            var afterBatch = sourceInputs.drain();
            var explicitTwo = afterBatch.stream()
                .filter(KafkaSourceInput.RequestNextPartitionBatch.class::isInstance)
                .map(KafkaSourceInput.RequestNextPartitionBatch.class::cast)
                .map(KafkaSourceInput.RequestNextPartitionBatch::requestId)
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(new PartitionBatchRequestId(generation, 2), explicitTwo);
            Assertions.assertEquals(List.of(0L, 1L, 2L, 3L), observedOffsets);

            var metrics = telemetry.getFinishedMetrics();
            Assertions.assertEquals(
                2,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.BATCH_REQUESTS_SUBMITTED
                )
            );
            Assertions.assertEquals(
                2,
                sumMetricPoints(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.BATCH_ENTITLEMENTS_RESOLVED
                )
            );
            Assertions.assertEquals(
                Set.of("BOOTSTRAP", "EXPLICIT"),
                metricAttributeValues(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.BATCH_ENTITLEMENTS_RESOLVED,
                    ReplayIntakeMetrics.BATCH_ENTITLEMENT_ATTRIBUTE
                )
            );
            Assertions.assertEquals(
                3,
                sumMetricPoints(metrics, ReplayIntakeMetrics.MetricNames.DEMAND_EVALUATIONS)
            );
        }
    }

    @Test
    void assignmentRecomputesDemandOverEveryAssignedGeneration() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var sourceInputs = new KafkaSourceInputQueue(new WakeupController(() -> {}, rootContext));
            var owner = new ReplayIntakeOwner(
                new ReplayIntakeInputQueue(),
                sourceInputs,
                new RecordingSink(() -> true, new CountDownLatch(0), new CountDownLatch(0)),
                failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
                rootContext.replayIntakeMetrics,
                ReplayIntakeOwner.RecordObserver.NOOP,
                new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
                2
            );
            var script = new RecordScript("traffic");
            var generation0 = script.generation(0);
            var generation1 = script.generation(1);

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation0));
            var firstAssignment = sourceInputs.drain();
            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation1));
            var secondAssignment = sourceInputs.drain();

            Assertions.assertEquals(
                Set.of(generation0),
                firstAssignment.stream()
                    .map(KafkaSourceInput.RequestNextPartitionBatch.class::cast)
                    .map(KafkaSourceInput.RequestNextPartitionBatch::generation)
                    .collect(java.util.stream.Collectors.toSet())
            );
            Assertions.assertEquals(
                Set.of(generation1),
                secondAssignment.stream()
                    .map(KafkaSourceInput.RequestNextPartitionBatch.class::cast)
                    .map(KafkaSourceInput.RequestNextPartitionBatch::generation)
                    .collect(java.util.stream.Collectors.toSet())
            );
            Assertions.assertEquals(
                3,
                sumMetricPoints(
                    telemetry.getFinishedMetrics(),
                    ReplayIntakeMetrics.MetricNames.DEMAND_EVALUATIONS
                ),
                "the first assignment evaluates one generation and the second evaluates both"
            );
        }
    }

    @Test
    void oneBatchMayOvershootDemandAndClosesItWithoutLossOrReordering() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var sourceInputs = new KafkaSourceInputQueue(new WakeupController(() -> {}, rootContext));
            var observedOffsets = new ArrayList<Long>();
            var sink = new RecordingSink(() -> true, new CountDownLatch(0), new CountDownLatch(0));
            var owner = new ReplayIntakeOwner(
                new ReplayIntakeInputQueue(),
                sourceInputs,
                sink,
                failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
                rootContext.replayIntakeMetrics,
                record -> observedOffsets.add(record.recordId().offset()),
                new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
                2
            );
            var response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
            var script = new RecordScript("traffic")
                .addTraffic(
                    0,
                    0,
                    Instant.ofEpochMilli(1_000),
                    "writer",
                    stream(
                        0,
                        read(1, "GET /0 HTTP/1.1\r\n\r\n"),
                        endOfMessage(2),
                        write(3, response)
                    )
                )
                .addTraffic(
                    0,
                    1,
                    Instant.ofEpochMilli(1_001),
                    "writer",
                    stream(
                        1,
                        read(4, "GET /1 HTTP/1.1\r\n\r\n"),
                        endOfMessage(5),
                        write(6, response)
                    )
                )
                .addTraffic(
                    0,
                    2,
                    Instant.ofEpochMilli(1_002),
                    "writer",
                    stream(
                        2,
                        read(7, "GET /2 HTTP/1.1\r\n\r\n"),
                        endOfMessage(8),
                        write(9, response),
                        close(10)
                    )
                )
                .addHeartbeat(0, 3, Instant.ofEpochMilli(1_003), "writer", 100)
                .addHeartbeat(0, 4, Instant.ofEpochMilli(1_004), "writer", 100);
            var generation = script.generation(0);

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
            var explicitOne = sourceInputs.drain().stream()
                .map(KafkaSourceInput.RequestNextPartitionBatch.class::cast)
                .map(KafkaSourceInput.RequestNextPartitionBatch::requestId)
                .findFirst()
                .orElseThrow();
            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                new PartitionBatchRequestId(generation, 0),
                script.records().subList(0, 4)
            ));

            var state = owner.partitionState(generation).orElseThrow();
            Assertions.assertEquals(3, state.retryReadyRequestSupplyCount());
            Assertions.assertFalse(state.demandOpen(2), "one complete batch may overshoot N");
            Assertions.assertEquals(
                List.of(0L, 1L, 2L, 3L),
                observedOffsets,
                "satisfying demand inside a batch must not truncate its trailing record"
            );
            Assertions.assertEquals(
                List.of(0L, 1L, 2L),
                sink.requests.stream().map(ReplayRequestId::capturedRequestOrdinal).toList()
            );
            Assertions.assertEquals(
                sink.requests,
                sink.completeResponses,
                "every request and complete response must survive in delivery order"
            );
            Assertions.assertTrue(
                sourceInputs.drain().stream()
                    .noneMatch(KafkaSourceInput.RequestNextPartitionBatch.class::isInstance),
                "the overlapping explicit entitlement remains the only intake-issued request"
            );

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                explicitOne,
                List.of(script.records().get(4))
            ));

            Assertions.assertEquals(List.of(0L, 1L, 2L, 3L, 4L), observedOffsets);
            Assertions.assertTrue(
                sourceInputs.drain().stream()
                    .noneMatch(KafkaSourceInput.RequestNextPartitionBatch.class::isInstance),
                "after the overlapping batch is fully applied, satisfied demand issues no next request"
            );
            Assertions.assertEquals(
                Set.of("OPEN", "SATISFIED"),
                metricAttributeValues(
                    telemetry.getFinishedMetrics(),
                    ReplayIntakeMetrics.MetricNames.DEMAND_EVALUATIONS,
                    ReplayIntakeMetrics.DEMAND_STATE_ATTRIBUTE
                )
            );
        }
    }

    @Test
    void largeBatchStillAppliesTrailingHeartbeatEvidenceWithoutRecordOrByteCap() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var sourceInputs = new KafkaSourceInputQueue(new WakeupController(() -> {}, rootContext));
            var observedOffsets = new ArrayList<Long>();
            var owner = new ReplayIntakeOwner(
                new ReplayIntakeInputQueue(),
                sourceInputs,
                new RecordingSink(() -> true, new CountDownLatch(0), new CountDownLatch(0)),
                failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
                ReplayIntakeOwner.Metrics.NOOP,
                record -> observedOffsets.add(record.recordId().offset()),
                new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
                2
            );
            var script = new RecordScript("traffic");
            var payload = "x".repeat(8_192);
            for (int offset = 0; offset < 64; offset++) {
                script.addProbe(
                    0,
                    offset,
                    Instant.ofEpochMilli(1_000 + offset),
                    "writer",
                    payload + offset
                );
            }
            script.addHeartbeat(0, 64, Instant.ofEpochMilli(1_064), "writer", 100);
            var generation = script.generation(0);

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
            sourceInputs.drain();
            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                new PartitionBatchRequestId(generation, 0),
                script.records()
            ));

            Assertions.assertEquals(65, observedOffsets.size());
            Assertions.assertEquals(64L, observedOffsets.get(observedOffsets.size() - 1));
            Assertions.assertEquals(
                1_064,
                owner.partitionState(generation).orElseThrow().greatestObservedLogAppendTime(),
                "the trailing heartbeat remains reachable despite all preceding record bytes"
            );
        }
    }

    @Test
    void aPayloadlessRecordWakesAnActiveSourcePollWithItsProtocolViolation() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var wakeups = new AtomicInteger();
            var wakeupController = new WakeupController(wakeups::incrementAndGet, rootContext);
            var sourceInputs = new KafkaSourceInputQueue(wakeupController);
            var owner = new ReplayIntakeOwner(
                new ReplayIntakeInputQueue(),
                sourceInputs,
                new RecordingSink(() -> true, new CountDownLatch(0), new CountDownLatch(0)),
                failure -> Assertions.fail("replay intake failed: " + failure.getMessage()),
                rootContext.replayIntakeMetrics
            );
            var script = new RecordScript("traffic")
                .addPayloadNotSet(0, 0, Instant.ofEpochMilli(1_000), "writer");
            var generation = script.generation(0);

            owner.applyOnCallingThread(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
            wakeupController.enterPoll();
            try {
                owner.applyOnCallingThread(new ReplayIntakeInput.PartitionRecordBatch(
                    new PartitionBatchRequestId(generation, 0),
                    script.records()
                ));
            } finally {
                wakeupController.leavePollAndConsumeWakeup();
            }

            Assertions.assertEquals(1, wakeups.get(), "the queued violation must interrupt an active poll");
            Assertions.assertTrue(
                sourceInputs.drain().stream()
                    .anyMatch(KafkaSourceInput.CaptureProtocolViolationDetected.class::isInstance),
                "the wakeup must point at the queued protocol-violation input"
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    telemetry.getFinishedMetrics(),
                    IKafkaConsumerContexts.MetricNames.POLLS_WOKEN_BY_QUEUED_INPUT
                )
            );
        }
    }

    @Test
    void stopAfterDrainingAppliesEveryAcceptedInputOnTheOwnerThreadAndRecordsIt() throws Exception {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var intakeInputs = new ReplayIntakeInputQueue();
            var sourceInputs = new KafkaSourceInputQueue(new WakeupController(() -> {}, rootContext));
            var ownerRef = new AtomicReference<ReplayIntakeOwner>();
            var fatal = new AtomicReference<Error>();
            var callbackEntered = new CountDownLatch(1);
            var releaseCallback = new CountDownLatch(1);
            var sink = new RecordingSink(
                () -> ownerRef.get().isOwnerThread(),
                callbackEntered,
                releaseCallback
            );
            var owner = new ReplayIntakeOwner(
                intakeInputs,
                sourceInputs,
                sink,
                fatal::set,
                rootContext.replayIntakeMetrics
            );
            ownerRef.set(owner);

            var script = new RecordScript("traffic").addTraffic(
                0,
                0,
                Instant.ofEpochMilli(1_000),
                "writer",
                stream(
                    read(1, "GET /threaded HTTP/1.1\r\n\r\n"),
                    endOfMessage(2),
                    close(3)
                )
            );
            var generation = script.generation(0);

            owner.start();
            Assertions.assertTrue(
                intakeInputs.submit(new ReplayIntakeInput.PartitionGenerationAssigned(generation))
            );
            Assertions.assertTrue(
                intakeInputs.submit(new ReplayIntakeInput.PartitionRecordBatch(
                    new PartitionBatchRequestId(generation, 0),
                    script.records()
                ))
            );
            Assertions.assertTrue(
                callbackEntered.await(5, TimeUnit.SECONDS),
                "the owner never reached the accepted batch"
            );
            Assertions.assertTrue(intakeInputs.requestStopAfterDraining());
            Assertions.assertFalse(
                intakeInputs.submit(new ReplayIntakeInput.PartitionGenerationAssigned(generation)),
                "nothing may be accepted after the FIFO stop marker"
            );
            Assertions.assertFalse(
                owner.termination().toCompletableFuture().isDone(),
                "the stop marker must wait behind the accepted batch"
            );
            releaseCallback.countDown();

            owner.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);

            Assertions.assertNull(fatal.get(), () -> "owner failed: " + fatal.get());
            Assertions.assertEquals(1, sink.requests.size());
            Assertions.assertEquals(1, sink.closes.size());
            Assertions.assertTrue(sink.everyCallbackUsedOwnerThread);

            var metrics = telemetry.getFinishedMetrics();
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.OWNER_STARTED
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.OWNER_STOPPED_AFTER_DRAINING
                )
            );
            Assertions.assertEquals(
                2,
                sumMetricPoints(metrics, ReplayIntakeMetrics.MetricNames.INPUTS_APPLIED)
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.RECORDS_APPLIED
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.REQUESTS_RECONSTITUTED
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.RESPONSES_UNPROVEN_COMPLETE
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    ReplayIntakeMetrics.MetricNames.CAPTURED_CLOSES_ACCEPTED
                )
            );
        }
    }

    private static long sumMetricPoints(
        Iterable<io.opentelemetry.sdk.metrics.data.MetricData> metrics,
        String metricName
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(metricName)) {
                return metric.getLongSumData().getPoints().stream()
                    .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                    .sum();
            }
        }
        return 0;
    }

    private static Set<String> metricAttributeValues(
        Iterable<io.opentelemetry.sdk.metrics.data.MetricData> metrics,
        String metricName,
        io.opentelemetry.api.common.AttributeKey<String> attributeKey
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(metricName)) {
                return metric.getLongSumData().getPoints().stream()
                    .map(point -> point.getAttributes().get(attributeKey))
                    .collect(java.util.stream.Collectors.toSet());
            }
        }
        return Set.of();
    }

    private static final class RecordingSink implements SourceAssemblySink {
        private final BooleanSupplier currentThreadIsOwner;
        private final CountDownLatch callbackEntered;
        private final CountDownLatch releaseCallback;
        private final List<ReplayRequestId> requests = new ArrayList<>();
        private final List<ReplayRequestId> completeResponses = new ArrayList<>();
        private final List<ConnectionProcessingId> closes = new ArrayList<>();
        private boolean everyCallbackUsedOwnerThread = true;

        private RecordingSink(
            BooleanSupplier currentThreadIsOwner,
            CountDownLatch callbackEntered,
            CountDownLatch releaseCallback
        ) {
            this.currentThreadIsOwner = currentThreadIsOwner;
            this.callbackEntered = callbackEntered;
            this.releaseCallback = releaseCallback;
        }

        @Override
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant requestFirstByteSourceTime,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime
        ) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
            requests.add(replayRequestId);
            callbackEntered.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("owner interrupted while the FIFO fence was under test", e);
            }
        }

        @Override
        public void onSourceInterimResponse(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.InterimResponse interimResponse
        ) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
            completeResponses.add(replayRequestId);
        }

        @Override
        public void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
        }

        @Override
        public void onCapturedClose(
            ConnectionProcessingId connectionProcessingId,
            long capturedOrdinal,
            Instant closeTime
        ) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
            closes.add(connectionProcessingId);
        }

        @Override
        public void onConnectionOwnerFinished(ConnectionProcessingId connectionProcessingId) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
        }
    }

    private static TrafficStream stream(TrafficObservation... observations) {
        return stream(0, observations);
    }

    private static TrafficStream stream(int number, TrafficObservation... observations) {
        return TrafficStream.newBuilder()
            .setNodeId("writer")
            .setConnectionId("connection")
            .setNumber(number)
            .addAllSubStream(List.of(observations))
            .build();
    }

    private static TrafficObservation read(long sequence, String data) {
        return observation(sequence)
            .setRead(ReadObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation write(long sequence, String data) {
        return observation(sequence)
            .setWrite(WriteObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation endOfMessage(long sequence) {
        return observation(sequence)
            .setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance())
            .build();
    }

    private static TrafficObservation close(long sequence) {
        return observation(sequence).setClose(CloseObservation.getDefaultInstance()).build();
    }

    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(sequence).build())
            .setConnectionObservationSequence(sequence);
    }
}
