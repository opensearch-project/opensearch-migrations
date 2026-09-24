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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.ReplayIntakeMetrics;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

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
    void stopAfterDrainingAppliesEveryAcceptedInputOnTheOwnerThreadAndRecordsIt() throws Exception {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var intakeInputs = new ReplayIntakeInputQueue();
            var sourceInputs = new KafkaSourceInputQueue(new WakeupController(() -> {}, rootContext));
            var ownerRef = new AtomicReference<ReplayIntakeOwner>();
            var fatal = new AtomicReference<Error>();
            var sink = new RecordingSink(() -> ownerRef.get().isOwnerThread());
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
                    new PartitionBatchRequestId(generation, 1),
                    script.records()
                ))
            );
            Assertions.assertTrue(intakeInputs.requestStopAfterDraining());
            Assertions.assertFalse(
                intakeInputs.submit(new ReplayIntakeInput.PartitionGenerationAssigned(generation)),
                "nothing may be accepted after the FIFO stop marker"
            );

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

    private static final class RecordingSink implements SourceAssemblySink {
        private final BooleanSupplier currentThreadIsOwner;
        private final List<ReplayRequestId> requests = new ArrayList<>();
        private final List<ConnectionProcessingId> closes = new ArrayList<>();
        private boolean everyCallbackUsedOwnerThread = true;

        private RecordingSink(BooleanSupplier currentThreadIsOwner) {
            this.currentThreadIsOwner = currentThreadIsOwner;
        }

        @Override
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant sourceEventTime,
            long requestCompletingLogAppendTime
        ) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
            requests.add(replayRequestId);
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
        }

        @Override
        public void onSourceResponseIncomplete(ReplayRequestId replayRequestId, IncompleteReason reason) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
        }

        @Override
        public void onCapturedClose(ConnectionProcessingId connectionProcessingId, Instant closeTime) {
            everyCallbackUsedOwnerThread &= currentThreadIsOwner.getAsBoolean();
            closes.add(connectionProcessingId);
        }
    }

    private static TrafficStream stream(TrafficObservation... observations) {
        return TrafficStream.newBuilder()
            .setNodeId("writer")
            .setConnectionId("connection")
            .setNumber(0)
            .addAllSubStream(List.of(observations))
            .build();
    }

    private static TrafficObservation read(long sequence, String data) {
        return observation(sequence)
            .setRead(ReadObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
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
