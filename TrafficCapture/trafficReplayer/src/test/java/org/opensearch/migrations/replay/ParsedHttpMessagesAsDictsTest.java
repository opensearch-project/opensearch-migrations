package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ParsedHttpMessagesAsDictsTest extends InstrumentationTest {

    private static final byte[] COMPLETE_REQUEST =
        "GET / HTTP/1.1\r\nHost: source.example\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PARTIAL_SOURCE_RESPONSE =
        "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMPLETE_TARGET_RESPONSE =
        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    ParsedHttpMessagesAsDicts makeTestData(Map<String, Object> sourceResponse, Map<String, Object> targetResponse) {
        return new ParsedHttpMessagesAsDicts(
            rootContext.getTestTupleContext(),
            Optional.empty(),
            Optional.ofNullable(sourceResponse),
            Optional.empty(),
            List.of(targetResponse)
        );
    }

    @Test
    void expiredSourceResponseIsExplicitAndPartialBytesAreNotSerialized() {
        var sourcePair = makeSourcePair(RequestResponsePacketPair.ReconstructionStatus.EXPIRED_PREMATURELY);
        var targetResult = makeTargetResult(null);

        try (var tuple = new SourceTargetCaptureTuple(
            rootContext.getTestTupleContext(),
            sourcePair,
            targetResult,
            null
        )) {
            var tupleMap = new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple);

            Assertions.assertEquals(
                ParsedHttpMessagesAsDicts.SOURCE_RESPONSE_STATUS_EXPIRED,
                tupleMap.get(ParsedHttpMessagesAsDicts.SOURCE_RESPONSE_STATUS_KEY)
            );
            Assertions.assertFalse(tupleMap.containsKey("sourceResponse"));
            Assertions.assertEquals(1, ((List<?>) tupleMap.get("targetResponses")).size());
        }
    }

    @Test
    void expiredSourceResponseStatusIsIndependentOfTargetFailure() {
        var sourcePair = makeSourcePair(RequestResponsePacketPair.ReconstructionStatus.EXPIRED_PREMATURELY);
        var targetFailure = new IllegalStateException("target failed");
        var targetResult = makeTargetResult(targetFailure);

        try (var tuple = new SourceTargetCaptureTuple(
            rootContext.getTestTupleContext(),
            sourcePair,
            targetResult,
            null
        )) {
            var tupleMap = new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple);

            Assertions.assertEquals(
                ParsedHttpMessagesAsDicts.SOURCE_RESPONSE_STATUS_EXPIRED,
                tupleMap.get(ParsedHttpMessagesAsDicts.SOURCE_RESPONSE_STATUS_KEY)
            );
            Assertions.assertEquals(1L, tupleMap.get("numErrors"));
            Assertions.assertFalse(tupleMap.containsKey("sourceResponse"));
        }
    }

    @Test
    void completeSourceResponseDoesNotReportExpiredStatus() {
        var sourcePair = makeSourcePair(RequestResponsePacketPair.ReconstructionStatus.COMPLETE);
        var targetResult = makeTargetResult(null);

        try (var tuple = new SourceTargetCaptureTuple(
            rootContext.getTestTupleContext(),
            sourcePair,
            targetResult,
            null
        )) {
            var tupleMap = new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple);

            Assertions.assertFalse(tupleMap.containsKey(ParsedHttpMessagesAsDicts.SOURCE_RESPONSE_STATUS_KEY));
            Assertions.assertTrue(tupleMap.containsKey("sourceResponse"));
        }
    }

    private RequestResponsePacketPair makeSourcePair(RequestResponsePacketPair.ReconstructionStatus status) {
        var trafficStreamKey = PojoTrafficStreamKeyAndContext.build(
            "writer",
            "connection",
            0,
            rootContext::createTrafficStreamContextForTest
        );
        var sourcePair = new RequestResponsePacketPair(trafficStreamKey, Instant.EPOCH, 0, 0);
        sourcePair.addRequestData(Instant.EPOCH, COMPLETE_REQUEST);
        sourcePair.addResponseData(Instant.EPOCH.plusMillis(1), status == RequestResponsePacketPair.ReconstructionStatus.COMPLETE
            ? COMPLETE_TARGET_RESPONSE
            : PARTIAL_SOURCE_RESPONSE);
        sourcePair.completionStatus = status;
        return sourcePair;
    }

    private TransformedTargetRequestAndResponseList makeTargetResult(Throwable targetFailure) {
        var targetResponsePackets = List.of(
            new AbstractMap.SimpleEntry<>(Instant.EPOCH.plusMillis(2), COMPLETE_TARGET_RESPONSE)
        );
        var targetResponse = new AggregatedRawResponse(
            null,
            COMPLETE_TARGET_RESPONSE.length,
            Duration.ofMillis(2),
            targetResponsePackets,
            targetFailure
        );
        return new TransformedTargetRequestAndResponseList(
            null,
            HttpRequestTransformationStatus.skipped(),
            targetResponse
        );
    }
}
