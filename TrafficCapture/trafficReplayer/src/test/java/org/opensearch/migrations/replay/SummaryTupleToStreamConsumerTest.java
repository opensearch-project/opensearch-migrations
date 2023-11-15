package org.opensearch.migrations.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 4)
class SummaryTupleToStreamConsumerTest {
    private static final String NODE_ID = "n";
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final String TEST_EXCEPTION_MESSAGE = "TEST_EXCEPTION";

    private static class CloseableLogSetup implements AutoCloseable {
        List<String> logEvents = new ArrayList<>();
        AbstractAppender testAppender;
        public CloseableLogSetup() {
            testAppender = new AbstractAppender(SummaryTupleToStreamConsumer.OUTPUT_TUPLE_JSON_LOGGER,
                    null, null, false, null) {
                @Override
                public void append(LogEvent event) {
                    logEvents.add(event.getMessage().getFormattedMessage());
                }
            };
            var tupleLogger = (Logger) LogManager.getLogger(SummaryTupleToStreamConsumer.OUTPUT_TUPLE_JSON_LOGGER);
            tupleLogger.setLevel(Level.ALL);
            testAppender.start();
            tupleLogger.setAdditive(false);
            tupleLogger.addAppender(testAppender);
            var loggerCtx = ((LoggerContext) LogManager.getContext(false));
        }

        @Override
        public void close() {
            var tupleLogger = (Logger) LogManager.getLogger(SummaryTupleToStreamConsumer.OUTPUT_TUPLE_JSON_LOGGER);
            tupleLogger.removeAppender(testAppender);
            testAppender.stop();
        }
    }

    @Test
    public void testTupleNewWithNullKeyThrows() {
        try (var closeableLogSetup = new CloseableLogSetup()) {
            Assertions.assertThrows(Exception.class,
                    () -> new SourceTargetCaptureTuple(null, null, null,
                            null, null, null, null));
            Assertions.assertEquals(0, closeableLogSetup.logEvents.size());
        }
    }

    @Test
    public void testOutputterWithNulls() throws IOException {
        var emptyTuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(new PojoTrafficStreamKey(NODE_ID,"c",0), 0, 0),
                null, null, null, null, null, null);
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var consumer = new SummaryTupleToStreamConsumer();
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output="+contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
        }
    }

    @Test
    public void testOutputterWithException() throws IOException {
        var exception = new Exception(TEST_EXCEPTION_MESSAGE);
        var emptyTuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(new PojoTrafficStreamKey(NODE_ID,"c",0), 0, 0),
                null, null, null, null,
                exception, null);
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var consumer = new SummaryTupleToStreamConsumer();
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output="+contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
            Assertions.assertTrue(contents.contains(TEST_EXCEPTION_MESSAGE));
        }
    }

    public static byte[] loadResourceAsBytes(String path) throws IOException {
        try (InputStream inputStream = SummaryTupleToStreamConsumerTest.class.getResourceAsStream(path)) {
            return inputStream.readAllBytes();
        }
    }

    @Test
    public void testOutputterForGet() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT =
                "" +
                        "{\n" +
                        "    \"sourceRequest\": {\n" +
                        "        \"Request-URI\": \"/test\",\n" +
                        "        \"Method\": \"GET\",\n" +
                        "        \"HTTP-Version\": \"HTTP/1.1\",\n" +
                        "        \"body\": \"\",\n" +
                        "        \"Host\": \"foo.example\",\n" +
                        "        \"auTHorization\": \"Basic YWRtaW46YWRtaW4=\",\n" +
                        "        \"Content-Type\": \"application/json\",\n" +
                        "        \"content-length\": \"0\"\n" +
                        "    },\n" +
                        "    \"sourceResponse\": {\n" +
                        "        \"HTTP-Version\": {\n" +
                        "            \"keepAliveDefault\": true\n" +
                        "        },\n" +
                        "        \"Status-Code\": 200,\n" +
                        "        \"Reason-Phrase\": \"OK\",\n" +
                        "        \"response_time_ms\": 0,\n" +
                        "        \"body\": \"SFRUUC8xLjEgMjAwIE9LDQpDb250ZW50LXRyYW5zZmVyLWVuY29kaW5nOiBjaHVua2VkDQpEYXRlOiBUaHUsIDA4IEp1biAyMDIzIDIzOjA2OjIzIEdNVA0KVHJhbnNmZXItZW5jb2Rpbmc6IGNodW5rZWQNCkNvbnRlbnQtdHlwZTogdGV4dC9wbGFpbg0KRnVudGltZTogY2hlY2tJdCENCg0KMWUNCkkgc2hvdWxkIGJlIGRlY3J5cHRlZCB0ZXN0ZXIhCg0KMA0KDQo=\",\n" +
                        "        \"Content-transfer-encoding\": \"chunked\",\n" +
                        "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\n" +
                        "        \"Content-type\": \"text/plain\",\n" +
                        "        \"Funtime\": \"checkIt!\",\n" +
                        "        \"content-length\": \"30\"\n" +
                        "    },\n" +
                        "    \"targetRequest\": {\n" +
                        "        \"Request-URI\": \"/test\",\n" +
                        "        \"Method\": \"GET\",\n" +
                        "        \"HTTP-Version\": \"HTTP/1.1\",\n" +
                        "        \"body\": \"\",\n" +
                        "        \"Host\": \"foo.example\",\n" +
                        "        \"auTHorization\": \"Basic YWRtaW46YWRtaW4=\",\n" +
                        "        \"Content-Type\": \"application/json\",\n" +
                        "        \"content-length\": \"0\"\n" +
                        "    },\n" +
                        "    \"targetResponse\": {\n" +
                        "        \"HTTP-Version\": {\n" +
                        "            \"keepAliveDefault\": true\n" +
                        "        },\n" +
                        "        \"Status-Code\": 200,\n" +
                        "        \"Reason-Phrase\": \"OK\",\n" +
                        "        \"response_time_ms\": 267,\n" +
                        "        \"body\": \"SFRUUC8xLjEgMjAwIE9LDQpDb250ZW50LXRyYW5zZmVyLWVuY29kaW5nOiBjaHVua2VkDQpEYXRlOiBUaHUsIDA4IEp1biAyMDIzIDIzOjA2OjIzIEdNVA0KVHJhbnNmZXItZW5jb2Rpbmc6IGNodW5rZWQNCkNvbnRlbnQtdHlwZTogdGV4dC9wbGFpbg0KRnVudGltZTogY2hlY2tJdCENCg0KMWUNCkkgc2hvdWxkIGJlIGRlY3J5cHRlZCB0ZXN0ZXIhCg0KMA0KDQo=\",\n" +
                        "        \"Content-transfer-encoding\": \"chunked\",\n" +
                        "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\n" +
                        "        \"Content-type\": \"text/plain\",\n" +
                        "        \"Funtime\": \"checkIt!\",\n" +
                        "        \"content-length\": \"30\"\n" +
                        "    },\n" +
                        "    \"connectionId\": \"c.0\"\n" +
                        "}";
        testOutputterForRequest("get_withAuthHeader.txt", EXPECTED_LOGGED_OUTPUT);
    }

    @Test
    public void testOutputterForPost() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "" +
                "{\n" +
                "    \"sourceRequest\": {\n" +
                "        \"Request-URI\": \"/test\",\n" +
                "        \"Method\": \"POST\",\n" +
                "        \"HTTP-Version\": \"HTTP/1.1\",\n" +
                "        \"body\": \"UE9TVCAvdGVzdCBIVFRQLzEuMQpIb3N0OiBmb28uZXhhbXBsZQpDb250ZW50LVR5cGU6IGFwcGxpY2F0aW9uL2pzb24KQ29udGVudC1MZW5ndGg6IDYxNgoKewogICJzZXR0aW5ncyI6IHsKICAgICJpbmRleCI6IHsKICAgICAgIm51bWJlcl9vZl9zaGFyZHMiOiA3LAogICAgICAibnVtYmVyX29mX3JlcGxpY2FzIjogMwogICAgfSwKICAgICJhbmFseXNpcyI6IHsKICAgICAgImFuYWx5emVyIjogewogICAgICAgICJuYW1lQW5hbHl6ZXIiOiB7CiAgICAgICAgICAidHlwZSI6ICJjdXN0b20iLAogICAgICAgICAgInRva2VuaXplciI6ICJrZXl3b3JkIiwKICAgICAgICAgICJmaWx0ZXIiOiAidXBwZXJjYXNlIgogICAgICAgIH0KICAgICAgfQogICAgfQogIH0sCiAgIm1hcHBpbmdzIjogewogICAgImVtcGxveWVlIjogewogICAgICAicHJvcGVydGllcyI6IHsKICAgICAgICAiYWdlIjogewogICAgICAgICAgInR5cGUiOiAibG9uZyIKICAgICAgICB9LAogICAgICAgICJsZXZlbCI6IHsKICAgICAgICAgICJ0eXBlIjogImxvbmciCiAgICAgICAgfSwKICAgICAgICAidGl0bGUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IgogICAgICAgIH0sCiAgICAgICAgIm5hbWUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IiwKICAgICAgICAgICJhbmFseXplciI6ICJuYW1lQW5hbHl6ZXIiCiAgICAgICAgfQogICAgICB9CiAgICB9CiAgfQp9Cg==\",\n" +
                "        \"Host\": \"foo.example\",\n" +
                "        \"Content-Type\": \"application/json\",\n" +
                "        \"Content-Length\": \"616\"\n" +
                "    },\n" +
                "    \"sourceResponse\": {\n" +
                "        \"HTTP-Version\": {\n" +
                "            \"keepAliveDefault\": true\n" +
                "        },\n" +
                "        \"Status-Code\": 200,\n" +
                "        \"Reason-Phrase\": \"OK\",\n" +
                "        \"response_time_ms\": 0,\n" +
                "        \"body\": \"SFRUUC8xLjEgMjAwIE9LDQpDb250ZW50LXRyYW5zZmVyLWVuY29kaW5nOiBjaHVua2VkDQpEYXRlOiBUaHUsIDA4IEp1biAyMDIzIDIzOjA2OjIzIEdNVA0KVHJhbnNmZXItZW5jb2Rpbmc6IGNodW5rZWQNCkNvbnRlbnQtdHlwZTogdGV4dC9wbGFpbg0KRnVudGltZTogY2hlY2tJdCENCg0KMWUNCkkgc2hvdWxkIGJlIGRlY3J5cHRlZCB0ZXN0ZXIhCg0KMA0KDQo=\",\n" +
                "        \"Content-transfer-encoding\": \"chunked\",\n" +
                "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\n" +
                "        \"Content-type\": \"text/plain\",\n" +
                "        \"Funtime\": \"checkIt!\",\n" +
                "        \"content-length\": \"30\"\n" +
                "    },\n" +
                "    \"targetRequest\": {\n" +
                "        \"Request-URI\": \"/test\",\n" +
                "        \"Method\": \"POST\",\n" +
                "        \"HTTP-Version\": \"HTTP/1.1\",\n" +
                "        \"body\": \"UE9TVCAvdGVzdCBIVFRQLzEuMQpIb3N0OiBmb28uZXhhbXBsZQpDb250ZW50LVR5cGU6IGFwcGxpY2F0aW9uL2pzb24KQ29udGVudC1MZW5ndGg6IDYxNgoKewogICJzZXR0aW5ncyI6IHsKICAgICJpbmRleCI6IHsKICAgICAgIm51bWJlcl9vZl9zaGFyZHMiOiA3LAogICAgICAibnVtYmVyX29mX3JlcGxpY2FzIjogMwogICAgfSwKICAgICJhbmFseXNpcyI6IHsKICAgICAgImFuYWx5emVyIjogewogICAgICAgICJuYW1lQW5hbHl6ZXIiOiB7CiAgICAgICAgICAidHlwZSI6ICJjdXN0b20iLAogICAgICAgICAgInRva2VuaXplciI6ICJrZXl3b3JkIiwKICAgICAgICAgICJmaWx0ZXIiOiAidXBwZXJjYXNlIgogICAgICAgIH0KICAgICAgfQogICAgfQogIH0sCiAgIm1hcHBpbmdzIjogewogICAgImVtcGxveWVlIjogewogICAgICAicHJvcGVydGllcyI6IHsKICAgICAgICAiYWdlIjogewogICAgICAgICAgInR5cGUiOiAibG9uZyIKICAgICAgICB9LAogICAgICAgICJsZXZlbCI6IHsKICAgICAgICAgICJ0eXBlIjogImxvbmciCiAgICAgICAgfSwKICAgICAgICAidGl0bGUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IgogICAgICAgIH0sCiAgICAgICAgIm5hbWUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IiwKICAgICAgICAgICJhbmFseXplciI6ICJuYW1lQW5hbHl6ZXIiCiAgICAgICAgfQogICAgICB9CiAgICB9CiAgfQp9Cg==\",\n" +
                "        \"Host\": \"foo.example\",\n" +
                "        \"Content-Type\": \"application/json\",\n" +
                "        \"Content-Length\": \"616\"\n" +
                "    },\n" +
                "    \"targetResponse\": {\n" +
                "        \"HTTP-Version\": {\n" +
                "            \"keepAliveDefault\": true\n" +
                "        },\n" +
                "        \"Status-Code\": 200,\n" +
                "        \"Reason-Phrase\": \"OK\",\n" +
                "        \"response_time_ms\": 267,\n" +
                "        \"body\": \"SFRUUC8xLjEgMjAwIE9LDQpDb250ZW50LXRyYW5zZmVyLWVuY29kaW5nOiBjaHVua2VkDQpEYXRlOiBUaHUsIDA4IEp1biAyMDIzIDIzOjA2OjIzIEdNVA0KVHJhbnNmZXItZW5jb2Rpbmc6IGNodW5rZWQNCkNvbnRlbnQtdHlwZTogdGV4dC9wbGFpbg0KRnVudGltZTogY2hlY2tJdCENCg0KMWUNCkkgc2hvdWxkIGJlIGRlY3J5cHRlZCB0ZXN0ZXIhCg0KMA0KDQo=\",\n" +
                "        \"Content-transfer-encoding\": \"chunked\",\n" +
                "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\n" +
                "        \"Content-type\": \"text/plain\",\n" +
                "        \"Funtime\": \"checkIt!\",\n" +
                "        \"content-length\": \"30\"\n" +
                "    },\n" +
                "    \"connectionId\": \"c.0\"\n" +
                "}";
        testOutputterForRequest("post_formUrlEncoded_withFixedLength.txt", EXPECTED_LOGGED_OUTPUT);
    }

    private void testOutputterForRequest(String requestResourceName, String expected) throws IOException {
        var trafficStreamKey = new PojoTrafficStreamKey(NODE_ID,"c",0);
        var sourcePair = new RequestResponsePacketPair(trafficStreamKey);
        var rawRequestData = loadResourceAsBytes("/requests/raw/" + requestResourceName);
        sourcePair.addRequestData(Instant.EPOCH, rawRequestData);
        var rawResponseData = NettyPacketToHttpConsumerTest.EXPECTED_RESPONSE_STRING.getBytes(StandardCharsets.UTF_8);
        sourcePair.addResponseData(Instant.EPOCH, rawResponseData);

        var targetRequest = new TransformedPackets();
        targetRequest.add(Unpooled.wrappedBuffer(rawRequestData));
        var targetResponse = new ArrayList<byte[]>();
        targetResponse.add(rawResponseData);

        var tuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(trafficStreamKey, 0, 0),
                sourcePair, targetRequest, targetResponse, HttpRequestTransformationStatus.SKIPPED, null, Duration.ofMillis(267));
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var consumer = new SummaryTupleToStreamConsumer();
            consumer.accept(tuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output="+contents);
            Assertions.assertEquals(normalizeJson(expected), normalizeJson(contents));
        }
    }

    static String normalizeJson(String input) throws JsonProcessingException {
        return mapper.writeValueAsString(mapper.readTree(input));
    }
}