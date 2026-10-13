package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ParsedHttpMessagesAsDictsTest extends InstrumentationTest {

    private static final String REQ = "GET /test HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String RESP_200 = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
    private static final String INTERIM_100 = "HTTP/1.1 100 Continue\r\n\r\n";

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    ParsedHttpMessagesAsDicts makeTestData(Map<String, Object> sourceResponse, Map<String, Object> targetResponse) {
        return new ParsedHttpMessagesAsDicts(
            rootContext.getTestTupleContext(),
            Optional.empty(),
            Optional.ofNullable(sourceResponse),
            List.of(),
            Optional.empty(),
            List.of(targetResponse)
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void sourceAndTargetInterimResponsesAppearInTupleMap() {
        var tsKey = PojoTrafficStreamKeyAndContext.build("n", "c", 0,
            rootContext::createTrafficStreamContextForTest);
        var sourcePair = new RequestResponsePacketPair(tsKey, Instant.EPOCH, 0, 0);
        sourcePair.addRequestData(Instant.EPOCH, REQ.getBytes(StandardCharsets.UTF_8));
        sourcePair.addInterimResponseData(INTERIM_100.getBytes(StandardCharsets.UTF_8));
        sourcePair.addResponseData(Instant.EPOCH, RESP_200.getBytes(StandardCharsets.UTF_8));

        var targetRequest = new ByteBufList();
        targetRequest.add(Unpooled.wrappedBuffer(REQ.getBytes(StandardCharsets.UTF_8)));
        var targetResponsePackets = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        targetResponsePackets.add(new AbstractMap.SimpleEntry<>(Instant.now(),
            RESP_200.getBytes(StandardCharsets.UTF_8)));
        var targetInterimPackets = List.of(INTERIM_100.getBytes(StandardCharsets.UTF_8));
        var aggregated = new AggregatedRawResponse(null, targetInterimPackets, RESP_200.length(),
            Duration.ofMillis(10), targetResponsePackets, null);
        var transformedList = new TransformedTargetRequestAndResponseList(targetRequest,
            HttpRequestTransformationStatus.skipped(), aggregated);

        try (var ctx = rootContext.getTestTupleContext()) {
            var tuple = new SourceTargetCaptureTuple(ctx, sourcePair, transformedList, null);
            var dicts = new ParsedHttpMessagesAsDicts(tuple);
            var map = dicts.toTupleMap(tuple);

            var sourceInterims = (List<Map<String, Object>>) map.get("sourceInterimResponses");
            Assertions.assertNotNull(sourceInterims, () -> "expected sourceInterimResponses in: " + map);
            Assertions.assertEquals(1, sourceInterims.size());
            Assertions.assertEquals(100, sourceInterims.get(0).get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY));

            var targetResponses = (List<Map<String, Object>>) map.get("targetResponses");
            Assertions.assertEquals(1, targetResponses.size());
            var nestedInterims = (List<Map<String, Object>>) targetResponses.get(0).get("interimResponses");
            Assertions.assertNotNull(nestedInterims, () -> "expected interimResponses on target response: " + map);
            Assertions.assertEquals(1, nestedInterims.size());
            Assertions.assertEquals(100, nestedInterims.get(0).get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY));
        } finally {
            targetRequest.release();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void multipleInterimsPerTargetResponseAreAllRendered() {
        // Single attempt, two interim responses (e.g. two 103 Early Hints) before the final.
        var tsKey = PojoTrafficStreamKeyAndContext.build("n", "c", 0,
            rootContext::createTrafficStreamContextForTest);
        var sourcePair = new RequestResponsePacketPair(tsKey, Instant.EPOCH, 0, 0);
        sourcePair.addRequestData(Instant.EPOCH, REQ.getBytes(StandardCharsets.UTF_8));
        sourcePair.addResponseData(Instant.EPOCH, RESP_200.getBytes(StandardCharsets.UTF_8));

        var targetRequest = new ByteBufList();
        targetRequest.add(Unpooled.wrappedBuffer(REQ.getBytes(StandardCharsets.UTF_8)));
        var targetResponsePackets = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        targetResponsePackets.add(new AbstractMap.SimpleEntry<>(Instant.now(),
            RESP_200.getBytes(StandardCharsets.UTF_8)));
        var earlyHints1 = "HTTP/1.1 103 Early Hints\r\nLink: </a.css>; rel=preload\r\n\r\n";
        var earlyHints2 = "HTTP/1.1 103 Early Hints\r\nLink: </b.js>; rel=preload\r\n\r\n";
        var interimPackets = List.of(
            earlyHints1.getBytes(StandardCharsets.UTF_8),
            earlyHints2.getBytes(StandardCharsets.UTF_8));
        var aggregated = new AggregatedRawResponse(null, interimPackets, RESP_200.length(),
            Duration.ofMillis(10), targetResponsePackets, null);
        var transformedList = new TransformedTargetRequestAndResponseList(targetRequest,
            HttpRequestTransformationStatus.skipped(), aggregated);

        try (var ctx = rootContext.getTestTupleContext()) {
            var tuple = new SourceTargetCaptureTuple(ctx, sourcePair, transformedList, null);
            var map = new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple);
            var targetResponses = (List<Map<String, Object>>) map.get("targetResponses");
            var nestedInterims = (List<Map<String, Object>>) targetResponses.get(0).get("interimResponses");
            Assertions.assertEquals(2, nestedInterims.size(), () -> "expected two interim responses: " + map);
            Assertions.assertEquals(103, nestedInterims.get(0).get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY));
            Assertions.assertEquals(103, nestedInterims.get(1).get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY));
        } finally {
            targetRequest.release();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void retryAttemptsEachCarryTheirOwnInterimResponses() {
        // Two retry attempts: attempt 1 has a 100 Continue, attempt 2 has none.
        var tsKey = PojoTrafficStreamKeyAndContext.build("n", "c", 0,
            rootContext::createTrafficStreamContextForTest);
        var sourcePair = new RequestResponsePacketPair(tsKey, Instant.EPOCH, 0, 0);
        sourcePair.addRequestData(Instant.EPOCH, REQ.getBytes(StandardCharsets.UTF_8));
        sourcePair.addResponseData(Instant.EPOCH, RESP_200.getBytes(StandardCharsets.UTF_8));

        var targetRequest = new ByteBufList();
        targetRequest.add(Unpooled.wrappedBuffer(REQ.getBytes(StandardCharsets.UTF_8)));

        var attempt1Packets = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        attempt1Packets.add(new AbstractMap.SimpleEntry<>(Instant.now(),
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        var attempt1Interims = List.of(INTERIM_100.getBytes(StandardCharsets.UTF_8));
        var attempt1 = new AggregatedRawResponse(null, attempt1Interims, 64,
            Duration.ofMillis(5), attempt1Packets, null);

        var attempt2Packets = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        attempt2Packets.add(new AbstractMap.SimpleEntry<>(Instant.now(),
            RESP_200.getBytes(StandardCharsets.UTF_8)));
        var attempt2 = new AggregatedRawResponse(null, RESP_200.length(),
            Duration.ofMillis(8), attempt2Packets, null);

        var transformedList = new TransformedTargetRequestAndResponseList(targetRequest,
            HttpRequestTransformationStatus.skipped(), attempt1, attempt2);

        try (var ctx = rootContext.getTestTupleContext()) {
            var tuple = new SourceTargetCaptureTuple(ctx, sourcePair, transformedList, null);
            var map = new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple);
            var targetResponses = (List<Map<String, Object>>) map.get("targetResponses");
            Assertions.assertEquals(2, targetResponses.size());

            // Attempt 1 has its own interim list.
            var attempt1Interim = (List<Map<String, Object>>) targetResponses.get(0).get("interimResponses");
            Assertions.assertNotNull(attempt1Interim, () -> "attempt 1 should carry its interim: " + map);
            Assertions.assertEquals(1, attempt1Interim.size());
            Assertions.assertEquals(100, attempt1Interim.get(0).get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY));

            // Attempt 2 has none — interim key must be absent (not leak from attempt 1).
            Assertions.assertFalse(targetResponses.get(1).containsKey("interimResponses"),
                () -> "attempt 2 must not contain interim from attempt 1: " + map);
        } finally {
            targetRequest.release();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void noInterimResponsesProducesNoInterimKeys() {
        var tsKey = PojoTrafficStreamKeyAndContext.build("n", "c", 0,
            rootContext::createTrafficStreamContextForTest);
        var sourcePair = new RequestResponsePacketPair(tsKey, Instant.EPOCH, 0, 0);
        sourcePair.addRequestData(Instant.EPOCH, REQ.getBytes(StandardCharsets.UTF_8));
        sourcePair.addResponseData(Instant.EPOCH, RESP_200.getBytes(StandardCharsets.UTF_8));

        var targetRequest = new ByteBufList();
        targetRequest.add(Unpooled.wrappedBuffer(REQ.getBytes(StandardCharsets.UTF_8)));
        var targetResponsePackets = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        targetResponsePackets.add(new AbstractMap.SimpleEntry<>(Instant.now(),
            RESP_200.getBytes(StandardCharsets.UTF_8)));
        var aggregated = new AggregatedRawResponse(null, RESP_200.length(),
            Duration.ofMillis(10), targetResponsePackets, null);
        var transformedList = new TransformedTargetRequestAndResponseList(targetRequest,
            HttpRequestTransformationStatus.skipped(), aggregated);

        try (var ctx = rootContext.getTestTupleContext()) {
            var tuple = new SourceTargetCaptureTuple(ctx, sourcePair, transformedList, null);
            var map = new ParsedHttpMessagesAsDicts(tuple).toTupleMap(tuple);
            Assertions.assertFalse(map.containsKey("sourceInterimResponses"));
            var targetResponses = (List<Map<String, Object>>) map.get("targetResponses");
            Assertions.assertFalse(targetResponses.get(0).containsKey("interimResponses"));
        } finally {
            targetRequest.release();
        }
    }
}
