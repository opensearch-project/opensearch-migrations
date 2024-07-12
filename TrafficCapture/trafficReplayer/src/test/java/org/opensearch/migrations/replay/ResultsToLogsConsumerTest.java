package org.opensearch.migrations.replay;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.selector.ClassLoaderContextSelector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 4)
class ResultsToLogsConsumerTest extends InstrumentationTest {
    static {
        // Synchronize logging to for assertions
        LogManager.setFactory(new Log4jContextFactory(new ClassLoaderContextSelector()));
    }
    private static final String NODE_ID = "n";
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final String TEST_EXCEPTION_MESSAGE = "TEST_EXCEPTION";

    public final static String EXPECTED_RESPONSE_STRING = "HTTP/1.1 200 OK\r\n"
        + "Content-transfer-encoding: chunked\r\n"
        + "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n"
        + // This should be OK since it's always the same length
        "Transfer-encoding: chunked\r\n"
        + "Content-type: text/plain\r\n"
        + "Funtime: checkIt!\r\n"
        + "\r\n"
        + "1e\r\n"
        + "I should be decrypted tester!\r\n"
        + "\r\n"
        + "0\r\n"
        + "\r\n";

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

            testAppender = new AbstractAppender(instanceName, null, null, false, null) {
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
            Assertions.assertThrows(
                Exception.class,
                () -> new SourceTargetCaptureTuple(null, null, null, null, null, null, null)
            );
            Assertions.assertEquals(0, closeableLogSetup.logEvents.size());
        }
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterWithNulls() throws IOException {
        var emptyTuple = new SourceTargetCaptureTuple(
            rootContext.getTestTupleContext(),
            null,
            null,
            null,
            null,
            null,
            null
        );
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var resultsToLogsConsumer = new ResultsToLogsConsumer(closeableLogSetup.testLogger, null);
            var consumer = new TupleParserChainConsumer(resultsToLogsConsumer);
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output=" + contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
        }
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterWithException() {
        var exception = new Exception(TEST_EXCEPTION_MESSAGE);
        var emptyTuple = new SourceTargetCaptureTuple(
            rootContext.getTestTupleContext(),
            null,
            null,
            null,
            null,
            exception,
            null
        );
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var resultsToLogsConsumer = new ResultsToLogsConsumer(closeableLogSetup.testLogger, null);
            var consumer = new TupleParserChainConsumer(resultsToLogsConsumer);
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0);
            log.info("Output=" + contents);
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
        final String EXPECTED_LOGGED_OUTPUT = ""
            + "{\r\n"
            + "    \"sourceRequest\": {\r\n"
            + "        \"Request-URI\": \"/test\",\r\n"
            + "        \"Method\": \"GET\",\r\n"
            + "        \"HTTP-Version\": \"HTTP/1.1\",\r\n"
            + "        \"Host\": \"foo.example\",\r\n"
            + "        \"auTHorization\": \"Basic YWRtaW46YWRtaW4=\",\r\n"
            + "        \"Content-Type\": \"application/json\",\r\n"
            + "        \"content-length\": \"0\",\r\n"
            + "        \"body\": \"\"\r\n"
            + "    },\r\n"
            + "    \"sourceResponse\": {\r\n"
            + "        \"HTTP-Version\": {\r\n"
            + "            \"keepAliveDefault\": true\r\n"
            + "        },\r\n"
            + "        \"Status-Code\": 200,\r\n"
            + "        \"Reason-Phrase\": \"OK\",\r\n"
            + "        \"response_time_ms\": 0,\r\n"
            + "        \"Content-transfer-encoding\": \"chunked\",\r\n"
            + "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\r\n"
            + "        \"Content-type\": \"text/plain\",\r\n"
            + "        \"Funtime\": \"checkIt!\",\r\n"
            + "        \"content-length\": \"30\",\r\n"
            + "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEN\"\r\n"
            + "    },\r\n"
            + "    \"targetRequest\": {\r\n"
            + "        \"Request-URI\": \"/test\",\r\n"
            + "        \"Method\": \"GET\",\r\n"
            + "        \"HTTP-Version\": \"HTTP/1.1\",\r\n"
            + "        \"Host\": \"foo.example\",\r\n"
            + "        \"auTHorization\": \"Basic YWRtaW46YWRtaW4=\",\r\n"
            + "        \"Content-Type\": \"application/json\",\r\n"
            + "        \"content-length\": \"0\",\r\n"
            + "        \"body\": \"\"\r\n"
            + "    },\r\n"
            + "    \"targetResponse\": {\r\n"
            + "        \"HTTP-Version\": {\r\n"
            + "            \"keepAliveDefault\": true\r\n"
            + "        },\r\n"
            + "        \"Status-Code\": 200,\r\n"
            + "        \"Reason-Phrase\": \"OK\",\r\n"
            + "        \"response_time_ms\": 267,\r\n"
            + "        \"Content-transfer-encoding\": \"chunked\",\r\n"
            + "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\r\n"
            + "        \"Content-type\": \"text/plain\",\r\n"
            + "        \"Funtime\": \"checkIt!\",\r\n"
            + "        \"content-length\": \"30\",\r\n"
            + "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEN\"\r\n"
            + "    },\r\n"
            + "    \"connectionId\": \"testConnection.1\"\r\n"
            + "}";
        testOutputterForRequest("get_withAuthHeader.txt", EXPECTED_LOGGED_OUTPUT);
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterForPost() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = ""
            + "{\r\n"
            + "    \"sourceRequest\": {\r\n"
            + "        \"Request-URI\": \"/test\",\r\n"
            + "        \"Method\": \"POST\",\r\n"
            + "        \"HTTP-Version\": \"HTTP/1.1\",\r\n"
            + "        \"Host\": \"foo.example\",\r\n"
            + "        \"Content-Type\": \"application/json\",\r\n"
            + "        \"Content-Length\": \"652\",\r\n"
            + "        \"body\": \"ew0KICAic2V0dGluZ3MiOiB7DQogICAgImluZGV4Ijogew0KICAgICAgIm51bWJlcl9vZl9zaGFyZHMiOiA3LA0KICAgICAgIm51bWJlcl9vZl9yZXBsaWNhcyI6IDMNCiAgICB9LA0KICAgICJhbmFseXNpcyI6IHsNCiAgICAgICJhbmFseXplciI6IHsNCiAgICAgICAgIm5hbWVBbmFseXplciI6IHsNCiAgICAgICAgICAidHlwZSI6ICJjdXN0b20iLA0KICAgICAgICAgICJ0b2tlbml6ZXIiOiAia2V5d29yZCIsDQogICAgICAgICAgImZpbHRlciI6ICJ1cHBlcmNhc2UiDQogICAgICAgIH0NCiAgICAgIH0NCiAgICB9DQogIH0sDQogICJtYXBwaW5ncyI6IHsNCiAgICAiZW1wbG95ZWUiOiB7DQogICAgICAicHJvcGVydGllcyI6IHsNCiAgICAgICAgImFnZSI6IHsNCiAgICAgICAgICAidHlwZSI6ICJsb25nIg0KICAgICAgICB9LA0KICAgICAgICAibGV2ZWwiOiB7DQogICAgICAgICAgInR5cGUiOiAibG9uZyINCiAgICAgICAgfSwNCiAgICAgICAgInRpdGxlIjogew0KICAgICAgICAgICJ0eXBlIjogInRleHQiDQogICAgICAgIH0sDQogICAgICAgICJuYW1lIjogew0KICAgICAgICAgICJ0eXBlIjogInRleHQiLA0KICAgICAgICAgICJhbmFseXplciI6ICJuYW1lQW5hbHl6ZXIiDQogICAgICAgIH0NCiAgICAgIH0NCiAgICB9DQogIH0NCn0NCg==\"\r\n"
            + "    },\r\n"
            + "    \"sourceResponse\": {\r\n"
            + "        \"HTTP-Version\": {\r\n"
            + "            \"keepAliveDefault\": true\r\n"
            + "        },\r\n"
            + "        \"Status-Code\": 200,\r\n"
            + "        \"Reason-Phrase\": \"OK\",\r\n"
            + "        \"response_time_ms\": 0,\r\n"
            + "        \"Content-transfer-encoding\": \"chunked\",\r\n"
            + "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\r\n"
            + "        \"Content-type\": \"text/plain\",\r\n"
            + "        \"Funtime\": \"checkIt!\",\r\n"
            + "        \"content-length\": \"30\",\r\n"
            + "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEN\"\r\n"
            + "    },\r\n"
            + "    \"targetRequest\": {\r\n"
            + "        \"Request-URI\": \"/test\",\r\n"
            + "        \"Method\": \"POST\",\r\n"
            + "        \"HTTP-Version\": \"HTTP/1.1\",\r\n"
            + "        \"Host\": \"foo.example\",\r\n"
            + "        \"Content-Type\": \"application/json\",\r\n"
            + "        \"Content-Length\": \"652\",\r\n"
            + "        \"body\": \"ew0KICAic2V0dGluZ3MiOiB7DQogICAgImluZGV4Ijogew0KICAgICAgIm51bWJlcl9vZl9zaGFyZHMiOiA3LA0KICAgICAgIm51bWJlcl9vZl9yZXBsaWNhcyI6IDMNCiAgICB9LA0KICAgICJhbmFseXNpcyI6IHsNCiAgICAgICJhbmFseXplciI6IHsNCiAgICAgICAgIm5hbWVBbmFseXplciI6IHsNCiAgICAgICAgICAidHlwZSI6ICJjdXN0b20iLA0KICAgICAgICAgICJ0b2tlbml6ZXIiOiAia2V5d29yZCIsDQogICAgICAgICAgImZpbHRlciI6ICJ1cHBlcmNhc2UiDQogICAgICAgIH0NCiAgICAgIH0NCiAgICB9DQogIH0sDQogICJtYXBwaW5ncyI6IHsNCiAgICAiZW1wbG95ZWUiOiB7DQogICAgICAicHJvcGVydGllcyI6IHsNCiAgICAgICAgImFnZSI6IHsNCiAgICAgICAgICAidHlwZSI6ICJsb25nIg0KICAgICAgICB9LA0KICAgICAgICAibGV2ZWwiOiB7DQogICAgICAgICAgInR5cGUiOiAibG9uZyINCiAgICAgICAgfSwNCiAgICAgICAgInRpdGxlIjogew0KICAgICAgICAgICJ0eXBlIjogInRleHQiDQogICAgICAgIH0sDQogICAgICAgICJuYW1lIjogew0KICAgICAgICAgICJ0eXBlIjogInRleHQiLA0KICAgICAgICAgICJhbmFseXplciI6ICJuYW1lQW5hbHl6ZXIiDQogICAgICAgIH0NCiAgICAgIH0NCiAgICB9DQogIH0NCn0NCg==\"\r\n"
            + "    },\r\n"
            + "    \"targetResponse\": {\r\n"
            + "        \"HTTP-Version\": {\r\n"
            + "            \"keepAliveDefault\": true\r\n"
            + "        },\r\n"
            + "        \"Status-Code\": 200,\r\n"
            + "        \"Reason-Phrase\": \"OK\",\r\n"
            + "        \"response_time_ms\": 267,\r\n"
            + "        \"Content-transfer-encoding\": \"chunked\",\r\n"
            + "        \"Date\": \"Thu, 08 Jun 2023 23:06:23 GMT\",\r\n"
            + "        \"Content-type\": \"text/plain\",\r\n"
            + "        \"Funtime\": \"checkIt!\",\r\n"
            + "        \"content-length\": \"30\",\r\n"
            + "        \"body\": \"SSBzaG91bGQgYmUgZGVjcnlwdGVkIHRlc3RlciEN\"\r\n"
            + "    },\r\n"
            + "    \"connectionId\": \"testConnection.1\"\r\n"
            + "}";
        testOutputterForRequest("post_formUrlEncoded_withFixedLength.txt", EXPECTED_LOGGED_OUTPUT);
    }

    private void testOutputterForRequest(String requestResourceName, String expected) throws IOException {
        var trafficStreamKey = PojoTrafficStreamKeyAndContext.build(
            NODE_ID,
            "c",
            0,
            rootContext::createTrafficStreamContextForTest
        );
        var sourcePair = new RequestResponsePacketPair(trafficStreamKey, Instant.EPOCH, 0, 0);
        var rawRequestData = loadResourceAsBytes("/requests/raw/" + requestResourceName);
        sourcePair.addRequestData(Instant.EPOCH, rawRequestData);
        var rawResponseData = EXPECTED_RESPONSE_STRING.getBytes(StandardCharsets.UTF_8);
        sourcePair.addResponseData(Instant.EPOCH, rawResponseData);

        var targetRequest = new TransformedPackets();
        targetRequest.add(Unpooled.wrappedBuffer(rawRequestData));
        var targetResponse = new ArrayList<byte[]>();
        targetResponse.add(rawResponseData);

        try (var tupleContext = rootContext.getTestTupleContext(); var closeableLogSetup = new CloseableLogSetup()) {
            var tuple = new SourceTargetCaptureTuple(
                tupleContext,
                sourcePair,
                targetRequest,
                targetResponse,
                HttpRequestTransformationStatus.SKIPPED,
                null,
                Duration.ofMillis(267)
            );
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
        var allMetricData = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        var filteredMetrics = allMetricData.stream()
            .filter(md -> md.getName().startsWith("tupleResult"))
            .collect(Collectors.toList());
        // TODO - find out how to verify these metrics
        log.error("TODO - find out how to verify these metrics");
        // Assertions.assertEquals("REQUEST_ID:testConnection.1|SOURCE_HTTP_STATUS:200|TARGET_HTTP_STATUS:200|HTTP_STATUS_MATCH:1",
        // filteredMetrics.stream().map(md->md.getName()+":"+md.getData()).collect(Collectors.joining("|")));

    }

    static String normalizeJson(String input) throws JsonProcessingException {
        return mapper.writeValueAsString(mapper.readTree(input));
    }
}
