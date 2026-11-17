package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
import org.opensearch.migrations.replay.tracing.TupleSinkUploadMetrics;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.apache.kafka.common.TopicPartition;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class S3TupleSinkObservabilityTest {

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("TupleSinkUploadMetricsLogger")
    void innerSameKeyRetriesRemainPendingAndEmitOneDegradationAndRecovery() throws Exception {
        try (
            var telemetry = new InMemoryInstrumentationBundle(false, true);
            var logs = new TestLogAppender(TupleSinkUploadMetrics.class);
            var contexts = new ContextFixture(telemetry)
        ) {
            var requests = new CopyOnWriteArrayList<PutObjectRequest>();
            var attempts = new AtomicInteger();
            var successfulUpload = new CompletableFuture<PutObjectResponse>();
            var s3Client = scriptedClient(requests, attempts, successfulUpload);

            try (var sink = new S3TupleSink(
                s3Client,
                "tuple-bucket",
                "tuples/",
                "replayer-1",
                0,
                1024 * 1024,
                Duration.ofMinutes(10),
                1,
                Duration.ofMillis(10),
                contexts.root.getTupleSinkUploadMetrics()
            )) {
                var eventLoop = new TestEventLoop(new FakeClock());
                var fatalFailures = new ArrayList<Error>();
                var writer = new TupleWriter<>(
                    eventLoop,
                    new FakeClock(),
                    Duration.ofSeconds(1),
                    physicalSink(sink),
                    ignored -> {},
                    fatalFailures::add,
                    contexts.root.getTupleDurabilityMetrics()
                );

                var write = writer.write(
                    new TupleWriter.WriteTuple<>(contexts.tuple, tuple("connection-1"))
                );
                eventLoop.runUntilIdle();
                awaitAttempts(attempts, 3);

                Assertions.assertFalse(
                    write.completion().toCompletableFuture().isDone(),
                    "the outer durability future must remain pending while the inner writer retries"
                );
                Assertions.assertTrue(requests.stream().allMatch(request ->
                    request.bucket().equals("tuple-bucket")
                        && request.key().equals(requests.get(0).key())
                ));
                assertMetric(
                    telemetry,
                    TupleSinkUploadMetrics.MetricNames.RETRY_ATTEMPTS,
                    2
                );
                assertMetric(
                    telemetry,
                    TupleSinkUploadMetrics.MetricNames.RETRYING_OBJECTS,
                    1
                );
                assertMetric(
                    telemetry,
                    TupleDurabilityMetrics.MetricNames.WRITES_PENDING,
                    1
                );
                assertMetric(
                    telemetry,
                    TupleDurabilityMetrics.MetricNames.RETRIES,
                    0
                );
                Assertions.assertEquals(
                    1,
                    logs.count("tuple_sink_upload_degraded", Level.WARN)
                );
                Assertions.assertEquals(
                    0,
                    logs.count("tuple_sink_upload_retry_failed", Level.WARN)
                );
                Assertions.assertEquals(
                    0,
                    logs.count("tuple_sink_upload_retry_failed", Level.INFO)
                );
                Assertions.assertTrue(logs.contains(
                    "tuple_sink_upload_degraded",
                    "attempt=1",
                    "retry_delay_ms=10",
                    "failure_class=io",
                    "exception_type=ioexception",
                    "retrying_objects=1"
                ));
                var degradation = logs.events()
                    .stream()
                    .filter(event -> event.getMessage().getFormattedMessage()
                        .startsWith("event=tuple_sink_upload_degraded "))
                    .findFirst()
                    .orElseThrow();
                Assertions.assertInstanceOf(IOException.class, degradation.getThrown());

                successfulUpload.complete(PutObjectResponse.builder().build());
                awaitCompletion(write.completion().toCompletableFuture(), eventLoop);

                Assertions.assertInstanceOf(
                    TupleWriter.TupleDurable.class,
                    write.completion().toCompletableFuture().join()
                );
                assertMetric(
                    telemetry,
                    TupleSinkUploadMetrics.MetricNames.RECOVERIES,
                    1
                );
                assertMetric(
                    telemetry,
                    TupleSinkUploadMetrics.MetricNames.RETRYING_OBJECTS,
                    0
                );
                assertMetric(
                    telemetry,
                    TupleDurabilityMetrics.MetricNames.WRITES_PENDING,
                    0
                );
                assertMetric(
                    telemetry,
                    TupleDurabilityMetrics.MetricNames.RETRIES,
                    0
                );
                assertMetric(
                    telemetry,
                    TupleDurabilityMetrics.MetricNames.TERMINAL_FAILURES,
                    0
                );
                Assertions.assertEquals(
                    1,
                    logs.count("tuple_sink_upload_recovered", Level.INFO)
                );
                Assertions.assertTrue(logs.contains(
                    "tuple_sink_upload_recovered",
                    "successful_attempt=3",
                    "failed_attempts=2",
                    "retrying_objects=0"
                ));
                Assertions.assertTrue(fatalFailures.isEmpty());
                Assertions.assertEquals(0, writer.operations().activeCount());
            }
        }
    }

    private static S3AsyncClient scriptedClient(
        CopyOnWriteArrayList<PutObjectRequest> requests,
        AtomicInteger attempts,
        CompletableFuture<PutObjectResponse> successfulUpload
    ) {
        return (S3AsyncClient) Proxy.newProxyInstance(
            S3AsyncClient.class.getClassLoader(),
            new Class<?>[] {S3AsyncClient.class},
            (proxy, method, args) -> {
                if (method.getName().equals("putObject")) {
                    requests.add((PutObjectRequest) args[0]);
                    if (attempts.incrementAndGet() < 3) {
                        return CompletableFuture.failedFuture(new IOException("S3 unavailable"));
                    }
                    return successfulUpload;
                }
                if (method.getName().equals("close")) {
                    return null;
                }
                if (method.getName().equals("serviceName")) {
                    return "s3";
                }
                if (method.getName().equals("toString")) {
                    return "scripted-s3-client";
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static TupleWriter.PhysicalTupleSink<Map<String, Object>> physicalSink(
        S3TupleSink sink
    ) {
        return new TupleWriter.PhysicalTupleSink<>() {
            @Override
            public java.util.concurrent.CompletionStage<Void> write(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                var completion = new CompletableFuture<Void>();
                sink.accept(tuple, completion);
                return completion;
            }

            @Override
            public void flush() {
                sink.flush();
            }
        };
    }

    private static Map<String, Object> tuple(String connectionId) {
        var tuple = new LinkedHashMap<String, Object>();
        tuple.put("connectionId", connectionId);
        tuple.put("numRequests", 1);
        return tuple;
    }

    private static void awaitAttempts(AtomicInteger attempts, int expected)
        throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (attempts.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(expected, attempts.get());
    }

    private static void awaitCompletion(
        CompletableFuture<?> completion,
        TestEventLoop eventLoop
    ) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!completion.isDone() && System.nanoTime() < deadline) {
            eventLoop.runUntilIdle();
            Thread.sleep(10);
        }
        eventLoop.runUntilIdle();
        Assertions.assertTrue(completion.isDone(), "tuple durability did not recover");
    }

    private static void assertMetric(
        InMemoryInstrumentationBundle telemetry,
        String metricName,
        long expected
    ) {
        Assertions.assertEquals(
            expected,
            InMemoryInstrumentationBundle.getMetricValueOrZero(
                telemetry.getFinishedMetrics(),
                metricName
            ),
            metricName
        );
    }

    private static final class ContextFixture implements AutoCloseable {
        private static final ReplayRequestId REQUEST_ID = new ReplayRequestId(
            new ConnectionProcessingId(
                new PartitionGenerationId(new TopicPartition("traffic", 0), 1),
                new CapturedConnectionId("node", "connection"),
                1
            ),
            0
        );

        private final RootReplayerContext root;
        private final IReplayContexts.IKafkaRecordContext record;
        private final IReplayContexts.ITrafficStreamsLifecycleContext traffic;
        private final IReplayContexts.IRequestContext request;
        private final IReplayContexts.ITupleHandlingContext tuple;

        private ContextFixture(InMemoryInstrumentationBundle telemetry) {
            root = new RootReplayerContext(telemetry.openTelemetrySdk);
            record = root.createKafkaRecordContext(
                new KafkaRecordId(REQUEST_ID.connectionProcessingId().generation(), 0),
                0
            );
            traffic = record.createTrafficStreamContext(0);
            request = traffic.createRequestContext(REQUEST_ID, Instant.EPOCH);
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
