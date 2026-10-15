package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseDiagnostic;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseKind;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IReplayContexts.ITupleHandlingContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts.MetricNames;
import org.opensearch.migrations.tracing.TestContext;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TupleResponseTimingMetricsTest {
    private static final Instant SOURCE_REQUEST_END = Instant.EPOCH.plusSeconds(100);
    private static final Instant TARGET_REQUEST_SENT = Instant.EPOCH.plusSeconds(500);
    private static final double TOLERANCE = 1e-9;
    private static final List<String> TIMING_METRICS = List.of(
        MetricNames.TUPLE_SOURCE_FIRST_BYTE_DURATION,
        MetricNames.TUPLE_TARGET_FIRST_BYTE_DURATION,
        MetricNames.TUPLE_SOURCE_RESPONSE_DURATION,
        MetricNames.TUPLE_TARGET_RESPONSE_DURATION,
        MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE,
        MetricNames.TUPLE_RESPONSE_DURATION_RATIO
    );

    @Test
    void matchedTupleRecordsExactFractionalDurationsDifferencesAndRatios() {
        var metrics = recordTuple(fixture -> {
            fixture.sourceResponse = source(200, 2_250, 9_500);
            fixture.targetAttempts = List.of(obtained(target(200, 1_500, 20_000)));
        });

        assertHistogram(metrics, MetricNames.TUPLE_SOURCE_FIRST_BYTE_DURATION, method(), 2.25);
        assertHistogram(metrics, MetricNames.TUPLE_TARGET_FIRST_BYTE_DURATION, method(), 1.5);
        assertHistogram(metrics, MetricNames.TUPLE_SOURCE_RESPONSE_DURATION, method(), 9.5);
        assertHistogram(metrics, MetricNames.TUPLE_TARGET_RESPONSE_DURATION, method(), 20.0);
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE,
            difference(ITupleHandlingContext.FIRST_BYTE_SPAN, false),
            0.75
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE,
            difference(ITupleHandlingContext.TOTAL_SPAN, true),
            10.5
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_RATIO,
            ratio(ITupleHandlingContext.FIRST_BYTE_SPAN),
            1.5 / 2.25
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_RATIO,
            ratio(ITupleHandlingContext.TOTAL_SPAN),
            20.0 / 9.5
        );
        assertPointCount(metrics, MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE, 2);
        assertPointCount(metrics, MetricNames.TUPLE_RESPONSE_DURATION_RATIO, 2);
    }

    @Test
    void ratioFloorsEachInputAtOneMillisecondWhileDurationsAndDifferencesStayUnclamped() {
        var metrics = recordTuple(fixture -> {
            fixture.sourceResponse = source(200, 200, 400);
            fixture.targetAttempts = List.of(obtained(target(200, 500, 4_000)));
        });

        assertHistogram(metrics, MetricNames.TUPLE_SOURCE_FIRST_BYTE_DURATION, method(), 0.2);
        assertHistogram(metrics, MetricNames.TUPLE_TARGET_FIRST_BYTE_DURATION, method(), 0.5);
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE,
            difference(ITupleHandlingContext.FIRST_BYTE_SPAN, true),
            0.3
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_RATIO,
            ratio(ITupleHandlingContext.FIRST_BYTE_SPAN),
            1.0
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_RATIO,
            ratio(ITupleHandlingContext.TOTAL_SPAN),
            4.0
        );
    }

    @Test
    void equalSpansAreNotTargetSlower() {
        var metrics = recordTuple(fixture -> {
            fixture.sourceResponse = source(200, 3_000, 7_000);
            fixture.targetAttempts = List.of(obtained(target(200, 3_000, 7_000)));
        });

        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE,
            difference(ITupleHandlingContext.FIRST_BYTE_SPAN, false),
            0.0
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_DIFFERENCE,
            difference(ITupleHandlingContext.TOTAL_SPAN, false),
            0.0
        );
        assertHistogram(
            metrics,
            MetricNames.TUPLE_RESPONSE_DURATION_RATIO,
            ratio(ITupleHandlingContext.TOTAL_SPAN),
            1.0
        );
    }

    @Test
    void retriesUseTheFinalAttemptThatObtainedAResponse() {
        var metrics = recordTuple(fixture -> fixture.targetAttempts = List.of(
            obtained(target(503, 1_000, 1_000)),
            obtained(target(200, 6_000, 8_000))
        ));

        assertHistogram(metrics, MetricNames.TUPLE_TARGET_FIRST_BYTE_DURATION, method(), 6.0);
        assertHistogram(metrics, MetricNames.TUPLE_TARGET_RESPONSE_DURATION, method(), 8.0);
    }

    @Test
    void statusMismatchRecordsNoTimingMetrics() {
        var metrics = recordTuple(fixture -> fixture.targetAttempts = List.of(obtained(target(500, 1_000, 2_000))));

        assertNoTimingMetrics(metrics);
    }

    @Test
    void missingSourceResponseRecordsNoTimingMetrics() {
        var metrics = recordTuple(fixture -> fixture.sourceResponse = null);

        assertNoTimingMetrics(metrics);
    }

    @Test
    void missingTargetResponseRecordsNoTimingMetrics() {
        var metrics = recordTuple(fixture -> fixture.targetAttempts = List.of(
            obtained(target(200, 1_000, 2_000)),
            new TargetAttemptOutcome.NoTargetResponseObtained<>(new NoTargetResponseDiagnostic(
                NoTargetResponseKind.READ_TIMEOUT,
                "timed out"
            ))
        ));

        assertNoTimingMetrics(metrics);
    }

    @Test
    void missingSourceTimestampRecordsNoTimingMetrics() {
        var metrics = recordTuple(fixture ->
            fixture.sourceResponse = sourceResponse(200, null, micros(SOURCE_REQUEST_END, 2_000)));

        assertNoTimingMetrics(metrics);
    }

    @Test
    void missingTargetRequestSentTimeRecordsNoTimingMetrics() {
        var metrics = recordTuple(fixture -> fixture.targetAttempts = List.of(obtained(targetResponse(
            200,
            null,
            micros(TARGET_REQUEST_SENT, 1_000),
            micros(TARGET_REQUEST_SENT, 2_000)
        ))));

        assertNoTimingMetrics(metrics);
    }

    @Test
    void responseThatBeganBeforeItsRequestEndedRecordsNoTimingMetrics() {
        var metrics = recordTuple(fixture -> fixture.sourceResponse =
            source(200, -1_000, 2_000));

        assertNoTimingMetrics(metrics);
    }

    @Test
    void targetFirstByteSkipsInterimResponsePackets() {
        var response = AggregatedRawResponse.builder(TARGET_REQUEST_SENT.minusMillis(5))
            .addResponsePacket(bytes("HTTP/1.1 100 Continue\r\n\r\n"), micros(TARGET_REQUEST_SENT, -2_000))
            .markInterimResponse()
            .addResponsePacket(
                bytes("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n"),
                micros(TARGET_REQUEST_SENT, 3_000)
            )
            .addHttpParsedResponseObject(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
            .addResponsePacket(bytes("body"), micros(TARGET_REQUEST_SENT, 5_000))
            .setRequestSentTime(TARGET_REQUEST_SENT)
            .build();

        var timing = ParsedHttpMessagesAsDicts.targetResponseTiming(List.of(obtained(response))).orElseThrow();

        Assertions.assertEquals(Duration.ofMillis(3), timing.firstByte());
        Assertions.assertEquals(Duration.ofMillis(5), timing.total());
    }

    @Test
    void targetFirstByteIsTheFirstPacketWithoutAnInterimResponse() {
        var response = AggregatedRawResponse.builder(TARGET_REQUEST_SENT.minusMillis(5))
            .addResponsePacket(bytes("HTTP/1.1 200 OK\r\n"), micros(TARGET_REQUEST_SENT, 1_000))
            .addResponsePacket(bytes("Content-Length: 0\r\n\r\n"), micros(TARGET_REQUEST_SENT, 4_000))
            .addHttpParsedResponseObject(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
            .setRequestSentTime(TARGET_REQUEST_SENT)
            .build();

        var timing = ParsedHttpMessagesAsDicts.targetResponseTiming(List.of(obtained(response))).orElseThrow();

        Assertions.assertEquals(Duration.ofMillis(1), timing.firstByte());
        Assertions.assertEquals(Duration.ofMillis(4), timing.total());
    }

    private static HttpMessageAndTimestamp.Response source(int status, long firstByteMicros, long totalMicros) {
        return sourceResponse(
            status,
            micros(SOURCE_REQUEST_END, firstByteMicros),
            micros(SOURCE_REQUEST_END, totalMicros)
        );
    }

    private static AggregatedRawResponse target(int status, long firstByteMicros, long totalMicros) {
        return targetResponse(
            status,
            TARGET_REQUEST_SENT,
            micros(TARGET_REQUEST_SENT, firstByteMicros),
            micros(TARGET_REQUEST_SENT, totalMicros)
        );
    }

    private static Instant micros(Instant base, long micros) {
        return base.plusNanos(micros * 1_000);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Attributes method() {
        return Attributes.of(ITupleHandlingContext.METHOD_KEY, "GET");
    }

    private static Attributes difference(String span, boolean targetSlower) {
        return Attributes.builder()
            .put(ITupleHandlingContext.METHOD_KEY, "GET")
            .put(ITupleHandlingContext.SPAN_KEY, span)
            .put(ITupleHandlingContext.TARGET_SLOWER_KEY, targetSlower)
            .build();
    }

    private static Attributes ratio(String span) {
        return Attributes.builder()
            .put(ITupleHandlingContext.METHOD_KEY, "GET")
            .put(ITupleHandlingContext.SPAN_KEY, span)
            .build();
    }

    private static void assertHistogram(
        Collection<MetricData> metrics,
        String name,
        Attributes attributes,
        double expectedValue
    ) {
        var point = histogramPoints(metrics, name).stream()
            .filter(candidate -> candidate.getAttributes().equals(attributes))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + name + " point with " + attributes));
        Assertions.assertEquals(1, point.getCount(), name);
        Assertions.assertEquals(expectedValue, point.getSum(), TOLERANCE, name);
    }

    private static void assertPointCount(Collection<MetricData> metrics, String name, int expected) {
        Assertions.assertEquals(expected, histogramPoints(metrics, name).size(), name);
    }

    private static void assertNoTimingMetrics(Collection<MetricData> metrics) {
        for (var name : TIMING_METRICS) {
            Assertions.assertEquals(List.of(), histogramPoints(metrics, name), name);
        }
        Assertions.assertTrue(
            metrics.stream().anyMatch(metric -> metric.getName().equals(MetricNames.TUPLE_COMPARISON)),
            "the tuple itself is still counted"
        );
    }

    private static List<HistogramPointData> histogramPoints(Collection<MetricData> metrics, String name) {
        return metrics.stream()
            .filter(metric -> metric.getName().equals(name))
            .flatMap(metric -> metric.getHistogramData().getPoints().stream())
            .filter(point -> point.getCount() > 0)
            .toList();
    }

    private static HttpMessageAndTimestamp.Response sourceResponse(int status, Instant firstData, Instant last) {
        var response = new HttpMessageAndTimestamp.Response(SOURCE_REQUEST_END);
        if (firstData != null) {
            response.noteDataTimestamp(firstData);
        }
        response.add(bytes("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\n\r\n"));
        response.setLastPacketTimestamp(last);
        return response;
    }

    private static AggregatedRawResponse targetResponse(int status, Instant requestSent, Instant first, Instant last) {
        var packets = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        var head = bytes("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\n\r\n");
        packets.add(new AbstractMap.SimpleEntry<>(first, head));
        packets.add(new AbstractMap.SimpleEntry<>(last, new byte[0]));
        return new AggregatedRawResponse(
            new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status)),
            packets.get(0).getValue().length,
            Duration.ofSeconds(1),
            packets,
            null,
            requestSent,
            first
        );
    }

    private static TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse> obtained(
        AggregatedRawResponse response
    ) {
        return new TargetAttemptOutcome.TargetResponseObtained<>(response);
    }

    /** Builds and handles one GET tuple, by default with matching 200s, and returns the metrics it recorded. */
    private static Collection<MetricData> recordTuple(Consumer<Fixture> customize) {
        var fixture = new Fixture();
        customize.accept(fixture);
        return fixture.run();
    }

    private static final class Fixture {
        private static final TopicPartition PARTITION = new TopicPartition("traffic", 0);
        HttpMessageAndTimestamp.Response sourceResponse =
            source(200, 1_000, 2_000);
        List<TargetAttemptOutcome<AggregatedRawResponse>> targetAttempts = List.of(obtained(target(200, 1_000, 2_000)));

        Collection<MetricData> run() {
            var generation = new PartitionGenerationId(PARTITION, 1);
            var connection =
                new ConnectionProcessingId(generation, new CapturedConnectionId("writer", "connection"), 0);
            var requestId = new ReplayRequestId(connection, 2);
            try (var root = TestContext.withTracking(false, true)) {
                var recordContext = root.createKafkaRecordContext(new KafkaRecordId(generation, 1), 0);
                var trafficContext = recordContext.createTrafficStreamContext(1);
                var requestContext = trafficContext.createRequestContext(requestId, Instant.EPOCH);
                requestContext.onRequestReconstituted();
                var tupleContext = requestContext.createTupleContext();
                var targetRequest =
                    Unpooled.copiedBuffer("GET /test HTTP/1.1\r\nHost: target\r\n\r\n", StandardCharsets.UTF_8);
                var packets = new ByteBufList(targetRequest);
                targetRequest.release();
                var producer = ByteBufListProducer.of(packets);
                producer.trackOwnership(root.getResourceOwnershipMetrics());
                var preparedRequest = new NettyPacketToHttpConsumer.PreparedRequest(producer, Duration.ZERO);
                var sourceRequest = new HttpMessageAndTimestamp.Request(SOURCE_REQUEST_END.minusMillis(1));
                sourceRequest.add(bytes("GET /test HTTP/1.1\r\nHost: source\r\n\r\n"));
                sourceRequest.setLastPacketTimestamp(SOURCE_REQUEST_END);
                var terminal = targetAttempts.reversed().stream()
                    .filter(TargetAttemptOutcome.TargetResponseObtained.class::isInstance)
                    .map(attempt -> (TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse>) attempt)
                    .findFirst()
                    .orElseThrow();
                var result = new RequestReplayOwner.RequestResult<>(
                    requestId,
                    sourceRequest,
                    preparedRequest,
                    HttpRequestTransformationStatus.completed(),
                    targetAttempts,
                    terminal,
                    sourceResponse == null
                        ? new RequestReplayOwner.IncompleteFinalSourceResponse<HttpMessageAndTimestamp.Response>(
                            "expired"
                        )
                        : new RequestReplayOwner.CompleteFinalSourceResponse<>(
                            sourceResponse,
                            SourceResponseCompletionEvidence.NEXT_REQUEST
                        )
                );
                new ParsedHttpMessagesAsDicts(tupleContext, result);
                tupleContext.close();
                preparedRequest.close();
                requestContext.close();
                trafficContext.close();
                recordContext.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
                return root.inMemoryInstrumentationBundle.getFinishedMetrics();
            }
        }
    }
}
