package org.opensearch.migrations.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.slf4j.LoggerFactory;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 4)
class ResultsToLogsConsumerTest extends InstrumentationTest {
    private static final String NODE_ID = "n";
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final String TEST_EXCEPTION_MESSAGE = "TEST_EXCEPTION";

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    private static class CloseableLogSetup implements AutoCloseable {
        List<String> logEvents = Collections.synchronizedList(new ArrayList<>());
        AbstractAppender testAppender;
        org.slf4j.Logger testLogger;
        org.apache.logging.log4j.core.Logger internalLogger;

        final String instanceName;

        public CloseableLogSetup() {
            instanceName = this.getClass().getName() + ".Thread" + Thread.currentThread().getId();

            testAppender = new AbstractAppender(instanceName,
                    null, null, false, null) {
                @Override
                public void append(LogEvent event) {
                    logEvents.add(event.getMessage().getFormattedMessage());
                }
            };

            testAppender.start();

            internalLogger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(instanceName);
            testLogger = LoggerFactory.getLogger(instanceName);

            // Cast to core.Logger to access internal methods
            internalLogger.setLevel(Level.ALL);
            internalLogger.setAdditive(false);
            internalLogger.addAppender(testAppender);
        }

        @Override
        public void close() {
            internalLogger.removeAppender(testAppender);
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
    @ResourceLock("TestContext")
    public void testOutputterWithNulls() throws IOException {

        var urk = new UniqueReplayerRequestKey(PojoTrafficStreamKeyAndContext.build(NODE_ID, "c", 0,
                rootContext::createTrafficStreamContextForTest), 0, 0);
        var emptyTuple = new SourceTargetCaptureTuple(rootContext.getTestTupleContext(),
                null, null, null, null, null, null);
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var resultsToLogsConsumer = new ResultsToLogsConsumer(closeableLogSetup.testLogger, null);
            var consumer = new TupleParserChainConsumer(resultsToLogsConsumer);
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output="+contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
        }
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterWithException() {
        var exception = new Exception(TEST_EXCEPTION_MESSAGE);
        var emptyTuple = new SourceTargetCaptureTuple(rootContext.getTestTupleContext(),
                null, null, null, null,
                exception, null);
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var resultsToLogsConsumer = new ResultsToLogsConsumer(closeableLogSetup.testLogger, null);
            var consumer = new TupleParserChainConsumer(resultsToLogsConsumer);
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output="+contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
            Assertions.assertTrue(contents.contains(TEST_EXCEPTION_MESSAGE));
        }
    }

    private static byte[] loadResourceAsBytes(String path) throws IOException {
        try (InputStream inputStream = ResultsToLogsConsumerTest.class.getResourceAsStream(path)) {
            return inputStream.readAllBytes();
        }
    }

    @Test
    @ResourceLock("TestContext")
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
                        "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEK\",\n" +
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
                        "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEK\",\n" +
                        "        \"Content-transfer-encoding\": \"chunked\",\n" +
                        "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\n" +
                        "        \"Content-type\": \"text/plain\",\n" +
                        "        \"Funtime\": \"checkIt!\",\n" +
                        "        \"content-length\": \"30\"\n" +
                        "    },\n" +
                        "    \"connectionId\": \"testConnection.1\"\n" +
                        "}";
        testOutputterForRequest("get_withAuthHeader.txt", EXPECTED_LOGGED_OUTPUT);
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterForPost() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "" +
                "{\n" +
                "    \"sourceRequest\": {\n" +
                "        \"Request-URI\": \"/test\",\n" +
                "        \"Method\": \"POST\",\n" +
                "        \"HTTP-Version\": \"HTTP/1.1\",\n" +
                "        \"body\": \"ewogICJzZXR0aW5ncyI6IHsKICAgICJpbmRleCI6IHsKICAgICAgIm51bWJlcl9vZl9zaGFyZHMiOiA3LAogICAgICAibnVtYmVyX29mX3JlcGxpY2FzIjogMwogICAgfSwKICAgICJhbmFseXNpcyI6IHsKICAgICAgImFuYWx5emVyIjogewogICAgICAgICJuYW1lQW5hbHl6ZXIiOiB7CiAgICAgICAgICAidHlwZSI6ICJjdXN0b20iLAogICAgICAgICAgInRva2VuaXplciI6ICJrZXl3b3JkIiwKICAgICAgICAgICJmaWx0ZXIiOiAidXBwZXJjYXNlIgogICAgICAgIH0KICAgICAgfQogICAgfQogIH0sCiAgIm1hcHBpbmdzIjogewogICAgImVtcGxveWVlIjogewogICAgICAicHJvcGVydGllcyI6IHsKICAgICAgICAiYWdlIjogewogICAgICAgICAgInR5cGUiOiAibG9uZyIKICAgICAgICB9LAogICAgICAgICJsZXZlbCI6IHsKICAgICAgICAgICJ0eXBlIjogImxvbmciCiAgICAgICAgfSwKICAgICAgICAidGl0bGUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IgogICAgICAgIH0sCiAgICAgICAgIm5hbWUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IiwKICAgICAgICAgICJhbmFseXplciI6ICJuYW1lQW5hbHl6ZXIiCiAgICAgICAgfQogICAgICB9CiAgICB9CiAgfQp9Cg==\",\n" +
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
                "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEK\",\n" +
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
                "        \"body\": \"ewogICJzZXR0aW5ncyI6IHsKICAgICJpbmRleCI6IHsKICAgICAgIm51bWJlcl9vZl9zaGFyZHMiOiA3LAogICAgICAibnVtYmVyX29mX3JlcGxpY2FzIjogMwogICAgfSwKICAgICJhbmFseXNpcyI6IHsKICAgICAgImFuYWx5emVyIjogewogICAgICAgICJuYW1lQW5hbHl6ZXIiOiB7CiAgICAgICAgICAidHlwZSI6ICJjdXN0b20iLAogICAgICAgICAgInRva2VuaXplciI6ICJrZXl3b3JkIiwKICAgICAgICAgICJmaWx0ZXIiOiAidXBwZXJjYXNlIgogICAgICAgIH0KICAgICAgfQogICAgfQogIH0sCiAgIm1hcHBpbmdzIjogewogICAgImVtcGxveWVlIjogewogICAgICAicHJvcGVydGllcyI6IHsKICAgICAgICAiYWdlIjogewogICAgICAgICAgInR5cGUiOiAibG9uZyIKICAgICAgICB9LAogICAgICAgICJsZXZlbCI6IHsKICAgICAgICAgICJ0eXBlIjogImxvbmciCiAgICAgICAgfSwKICAgICAgICAidGl0bGUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IgogICAgICAgIH0sCiAgICAgICAgIm5hbWUiOiB7CiAgICAgICAgICAidHlwZSI6ICJ0ZXh0IiwKICAgICAgICAgICJhbmFseXplciI6ICJuYW1lQW5hbHl6ZXIiCiAgICAgICAgfQogICAgICB9CiAgICB9CiAgfQp9Cg==\",\n" +
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
                "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEK\",\n" +
                "        \"Content-transfer-encoding\": \"chunked\",\n" +
                "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\n" +
                "        \"Content-type\": \"text/plain\",\n" +
                "        \"Funtime\": \"checkIt!\",\n" +
                "        \"content-length\": \"30\"\n" +
                "    },\n" +
                "    \"connectionId\": \"testConnection.1\"\n" +
                "}";
        testOutputterForRequest("post_formUrlEncoded_withFixedLength.txt", EXPECTED_LOGGED_OUTPUT);
    }

    private void testOutputterForRequest(String requestResourceName, String expected) throws IOException {
        var trafficStreamKey = PojoTrafficStreamKeyAndContext.build(NODE_ID, "c", 0,
                rootContext::createTrafficStreamContextForTest);
        var sourcePair = new RequestResponsePacketPair(trafficStreamKey, Instant.EPOCH,
                0, 0);
        var rawRequestData = loadResourceAsBytes("/requests/raw/" + requestResourceName);
        sourcePair.addRequestData(Instant.EPOCH, rawRequestData);
        var rawResponseData = NettyPacketToHttpConsumerTest.EXPECTED_RESPONSE_STRING.getBytes(StandardCharsets.UTF_8);
        sourcePair.addResponseData(Instant.EPOCH, rawResponseData);

        var targetRequest = new TransformedPackets();
        targetRequest.add(Unpooled.wrappedBuffer(rawRequestData));
        var targetResponse = new ArrayList<byte[]>();
        targetResponse.add(rawResponseData);

        try (var tupleContext = rootContext.getTestTupleContext();
             var closeableLogSetup = new CloseableLogSetup()) {
            var tuple = new SourceTargetCaptureTuple(tupleContext,
                    sourcePair, targetRequest, targetResponse, HttpRequestTransformationStatus.SKIPPED, null, Duration.ofMillis(267));
            var streamConsumer = new ResultsToLogsConsumer(closeableLogSetup.testLogger, null);
            var consumer = new TupleParserChainConsumer(streamConsumer);
            consumer.accept(tuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output=" + contents);
            Assertions.assertEquals(normalizeJson(expected), normalizeJson(contents));
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        var allMetricData = rootContext.inMemoryInstrumentationBundle.testMetricExporter.getFinishedMetricItems();
        var filteredMetrics = allMetricData.stream().filter(md->md.getName().startsWith("tupleResult"))
                .collect(Collectors.toList());
        // TODO - find out how to verify these metrics
        log.error("TODO - find out how to verify these metrics");
//        Assertions.assertEquals("REQUEST_ID:testConnection.1|SOURCE_HTTP_STATUS:200|TARGET_HTTP_STATUS:200|HTTP_STATUS_MATCH:1",
//                filteredMetrics.stream().map(md->md.getName()+":"+md.getData()).collect(Collectors.joining("|")));

    }

    static String normalizeJson(String input) throws JsonProcessingException {
        return mapper.writeValueAsString(mapper.readTree(input));
    }
}