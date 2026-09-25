package org.opensearch.migrations.replay.tracing;

import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TracingTest {
    @Test
    void scopedReplayContextsPreserveHierarchyAndPublishedMetricNames()
        throws IllegalAccessException {
        try (var telemetry = new InMemoryInstrumentationBundle(true, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            Assertions.assertSame(rootContext.replayIntakeMetrics, rootContext.getReplayIntakeMetrics());
            Assertions.assertSame(rootContext.kafkaCommitStateMetrics, rootContext.getKafkaCommitStateMetrics());
            Assertions.assertSame(
                rootContext.targetAttemptPermitMetrics,
                rootContext.getTargetAttemptPermitMetrics()
            );

            var connectionId = connectionId(7, 2);
            var requestId = new ReplayRequestId(connectionId, 11);
            try (var connectionContext = rootContext.createConnectionContext(connectionId)) {
                connectionContext.addFailedChannelCreation();
                try (var socketContext = connectionContext.createSocketContext()) {
                    Assertions.assertSame(
                        connectionContext,
                        socketContext.getLogicalEnclosingScope()
                    );
                }
            }

            var recordId = new KafkaRecordId(connectionId.generation(), 41);
            var recordContext = rootContext.createKafkaRecordContext(recordId, 127);
            var trafficContext = recordContext.createTrafficStreamContext(5);
            var requestContext = trafficContext.createRequestContext(
                requestId,
                Instant.EPOCH
            );
            Assertions.assertEquals(requestId, requestContext.getRequestId());
            requestContext.onRequestReconstituted();
            Assertions.assertThrows(
                IllegalStateException.class,
                requestContext::onRequestReconstituted,
                "the reconstitution transition and its metric must be one-shot"
            );
            try (var requestAccumulation = requestContext.createRequestAccumulationContext()) {}
            try (var responseAccumulation = requestContext.createResponseAccumulationContext()) {}
            try (var transformation = requestContext.createTransformationContext()) {
                transformation.onHeaderParse();
                transformation.onPayloadParse();
                transformation.onPayloadParseSuccess();
                transformation.onJsonPayloadParseRequired();
                transformation.onJsonPayloadParseSucceeded();
                transformation.onTextPayloadParseSucceeded();
                transformation.onTextPayloadParseFailed();
                transformation.onPayloadSetBinary();
                transformation.onPayloadBytesIn(101);
                transformation.onUncompressedBytesIn(102);
                transformation.onUncompressedBytesOut(103);
                transformation.onFinalBytesOut(104);
                transformation.onTransformSuccess();
                transformation.onTransformSkip();
                transformation.onTransformFailure();
                transformation.aggregateInputChunk(105);
                transformation.aggregateOutputChunk(106);
            }
            try (var scheduled = requestContext.createScheduledContext(Instant.now())) {}
            try (var target = requestContext.createTargetRequestContext()) {
                target.onBytesSent(109);
                target.onBytesReceived(110);
                try (var connecting = target.createHttpConnectingContext()) {}
                try (var sending = target.createHttpSendingContext()) {}
                try (var waiting = target.createWaitingForResponseContext()) {}
                try (var receiving = target.createHttpReceivingContext()) {}
            }
            try (var retry = requestContext.createTargetRequestContext()) {}
            try (var tuple = requestContext.createTupleContext()) {
                tuple.setSourceStatus(201);
                tuple.setTargetStatus(503);
                tuple.setMethod("POST");
                tuple.setEndpoint("/index/_doc");
                tuple.setHttpVersion("HTTP/1.1");
            }
            requestContext.close();
            trafficContext.close();
            recordContext.complete(IReplayContexts.RecordDisposition.COMMITTED);

            var publicTypes = List.of(
                IReplayContexts.class,
                IReplayContexts.IConnectionContext.class,
                IReplayContexts.IRequestContext.class,
                IRootReplayerContext.class
            );
            Assertions.assertTrue(
                publicTypes.stream()
                    .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                    .noneMatch(method -> method.getName().contains("PermitPool"))
            );
            Assertions.assertTrue(
                publicTypes.stream()
                    .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .noneMatch(type ->
                        type.getSimpleName().equals("ITrafficStreamKey")
                            || type.getSimpleName().equals("UniqueReplayerRequestKey"))
            );

            var recorded = telemetry.getFinishedMetrics();
            var recordedNames = recorded.stream()
                .map(MetricData::getName)
                .collect(java.util.stream.Collectors.toSet());
            for (var field : IReplayContexts.MetricNames.class.getFields()) {
                Assertions.assertTrue(
                    recordedNames.contains((String) field.get(null)),
                    () -> "Missing published metric " + field.getName()
                );
            }
            Assertions.assertEquals(33, IReplayContexts.MetricNames.class.getFields().length);
            assertLongPoint(
                recorded,
                IReplayContexts.MetricNames.TRANSFORM_TEXT_SUCCEEDED,
                Attributes.empty(),
                1
            );
            assertLongPoint(
                recorded,
                ReplayIntakeMetrics.MetricNames.REQUESTS_RECONSTITUTED,
                Attributes.empty(),
                1
            );
            assertLongPoint(
                recorded,
                IReplayContexts.MetricNames.ACTIVE_CHANNELS_YET_TO_BE_FULLY_DISCARDED,
                Attributes.empty(),
                0
            );
            assertLongPoint(
                recorded,
                IReplayContexts.MetricNames.ACTIVE_TARGET_CONNECTIONS,
                Attributes.empty(),
                0
            );
            assertLongPoint(
                recorded,
                IReplayContexts.MetricNames.TUPLE_COMPARISON,
                Attributes.builder()
                    .put(IReplayContexts.ITupleHandlingContext.SOURCE_STATUS_CODE_KEY, 200L)
                    .put(IReplayContexts.ITupleHandlingContext.TARGET_STATUS_CODE_KEY, 500L)
                    .put(IReplayContexts.ITupleHandlingContext.METHOD_KEY, "POST")
                    .put(IReplayContexts.ITupleHandlingContext.STATUS_CODE_MATCH_KEY, false)
                    .build(),
                1
            );
            var spans = telemetry.getFinishedSpans();
            assertParent(
                spans,
                IReplayContexts.ActivityNames.TRAFFIC_STREAM_LIFETIME,
                IReplayContexts.ActivityNames.RECORD_LIFETIME
            );
            assertParent(
                spans,
                IReplayContexts.ActivityNames.HTTP_TRANSACTION,
                IReplayContexts.ActivityNames.TRAFFIC_STREAM_LIFETIME
            );
            assertParent(
                spans,
                IReplayContexts.ActivityNames.TRANSFORMATION,
                IReplayContexts.ActivityNames.HTTP_TRANSACTION
            );
            assertParent(
                spans,
                IReplayContexts.ActivityNames.TARGET_TRANSACTION,
                IReplayContexts.ActivityNames.HTTP_TRANSACTION
            );
            assertParent(
                spans,
                IReplayContexts.ActivityNames.REQUEST_SENDING,
                IReplayContexts.ActivityNames.TARGET_TRANSACTION
            );
            assertParent(
                spans,
                IReplayContexts.ActivityNames.TUPLE_COMPARISON,
                IReplayContexts.ActivityNames.HTTP_TRANSACTION
            );
            var requestSpan = span(spans, IReplayContexts.ActivityNames.HTTP_TRANSACTION);
            Assertions.assertEquals(
                7,
                requestSpan.getAttributes().get(IReplayContexts.TraceAttributes.GENERATION)
            );
            Assertions.assertEquals(
                2,
                requestSpan.getAttributes().get(
                    IReplayContexts.TraceAttributes.CONNECTION_LIFETIME
                )
            );
            Assertions.assertEquals(
                11,
                requestSpan.getAttributes().get(IReplayContexts.TraceAttributes.REQUEST_ORDINAL)
            );
        }
    }

    private static ConnectionProcessingId connectionId(long generation, long lifetime) {
        return new ConnectionProcessingId(
            new PartitionGenerationId(new TopicPartition("topic", 5), generation),
            new CapturedConnectionId("writer", "connection"),
            lifetime
        );
    }

    private static void assertParent(
        List<SpanData> spans,
        String childName,
        String parentName
    ) {
        Assertions.assertEquals(
            span(spans, parentName).getSpanId(),
            span(spans, childName).getParentSpanId()
        );
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream()
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing span " + name));
    }

    private static void assertLongPoint(
        Iterable<MetricData> metrics,
        String name,
        Attributes attributes,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var point = metric.getLongSumData()
                    .getPoints()
                    .stream()
                    .filter(candidate -> candidate.getAttributes().equals(attributes))
                    .findFirst()
                    .orElseThrow();
                Assertions.assertEquals(expectedValue, point.getValue());
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }
}
