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
