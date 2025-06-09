package org.opensearch.migrations.replay;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.selector.ClassLoaderContextSelector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 4)
class ResultsToLogsConsumerTest extends InstrumentationTest {
    static {
        // Synchronize logging for assertions
        LogManager.setFactory(new Log4jContextFactory(new ClassLoaderContextSelector()));
    }
    private static final String NODE_ID = "n";
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final String TEST_EXCEPTION_MESSAGE = "TEST_EXCEPTION";

    public static final String EXPECTED_RESPONSE_STRING = "HTTP/1.1 200 OK\r\n"
        + "Content-transfer-encoding: chunked\r\n"
        + "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n"
        + "Transfer-encoding: chunked\r\n"
        + "Content-type: text/plain\r\n"
        + "Funtime: checkIt!\r\n"
        + "\r\n"
        + "1e\r\n"
        + "I should be decrypted tester!\r\n"
        + "\r\n"
        + "0\r\n"
        + "\r\n";

    public static final String EXPECTED_RESPONSE_STRING_HEAD = "HTTP/1.1 200 OK\r\n"
            + "Content-transfer-encoding: chunked\r\n"
            + "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n"
            + "Transfer-encoding: chunked\r\n"
            + "Content-type: text/plain\r\n"
            + "Funtime: checkIt!\r\n"
            + "\r\n";


    public static String calculateLoggerName(Class<?> clazz) {
        return clazz.getName() + ".Thread" + Thread.currentThread().getId();
    }

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    @Test
    public void testTupleNewWithNullKeyThrows() {
        var responses = new TransformedTargetRequestAndResponseList(null, HttpRequestTransformationStatus.skipped());
        try (var closeableLogSetup = new CloseableLogSetup(calculateLoggerName(this.getClass()))) {
            Assertions.assertThrows(
                Exception.class,
                () -> new SourceTargetCaptureTuple(null, null, responses, null)
            );
            Assertions.assertEquals(0, closeableLogSetup.getLogEvents().size());
        }
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterWithNulls() throws IOException {
        var responses = new TransformedTargetRequestAndResponseList(null, HttpRequestTransformationStatus.skipped());
        var emptyTuple = new SourceTargetCaptureTuple(rootContext.getTestTupleContext(), null, responses, null);
        try (var closeableLogSetup = new CloseableLogSetup(calculateLoggerName(this.getClass()))) {
            var resultsToLogsConsumer = new ResultsToLogsConsumer(closeableLogSetup.getTestLogger(), null, null);
            var consumer = new TupleParserChainConsumer(resultsToLogsConsumer);
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.getLogEvents().size());
            var contents = closeableLogSetup.getLogEvents().get(0);
            log.info("Output=" + contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
        }
    }

    @Test
    @ResourceLock("TestContext")
    public void testOutputterWithException() {
        var responses = new TransformedTargetRequestAndResponseList(null, HttpRequestTransformationStatus.skipped());
        var exception = new Exception(TEST_EXCEPTION_MESSAGE);
        var emptyTuple = new SourceTargetCaptureTuple(rootContext.getTestTupleContext(), null, responses, exception);
        try (var closeableLogSetup = new CloseableLogSetup(calculateLoggerName(this.getClass()))) {
            var resultsToLogsConsumer = new ResultsToLogsConsumer(closeableLogSetup.getTestLogger(), null, null);
            var consumer = new TupleParserChainConsumer(resultsToLogsConsumer);
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.getLogEvents().size());
            var contents = closeableLogSetup.getLogEvents().get(0);
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
    @Tag("longTest")
    @ResourceLock("TestContext")
    public void testOutputterForGet() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "{" +
            "\"sourceRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"auTHorization\": [ \"Basic YWRtaW46YWRtaW4=\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"GET\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"sourceResponse\": { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 0, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"I should be decrypted tester!\\r\" " +
            "    } " +
            "}, " +
            "\"targetRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"auTHorization\": [ \"Basic YWRtaW46YWRtaW4=\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"GET\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"targetResponses\": [ { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 267, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"I should be decrypted tester!\\r\" " +
            "    } " +
            "} ], " +
            "\"connectionId\": \"testConnection.1\", " +
            "\"numRequests\": 1, " +
            "\"numErrors\": 0 " +
            "}";
        testOutputterForRequest("get_withAuthHeader.txt", EXPECTED_LOGGED_OUTPUT, null);
    }

    @Test
    @Tag("longTest")
    @ResourceLock("TestContext")
    public void testOutputterForHead() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "{" +
            "\"sourceRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"auTHorization\": [ \"Basic YWRtaW46YWRtaW4=\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"HEAD\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"sourceResponse\": { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 0, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"targetRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"auTHorization\": [ \"Basic YWRtaW46YWRtaW4=\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"HEAD\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"targetResponses\": [ { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 267, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "} ], " +
            "\"connectionId\": \"testConnection.1\", " +
            "\"numRequests\": 1, " +
            "\"numErrors\": 0 " +
            "}";
        testOutputterForRequestWithTransformerSupplier("head_withAuthHeader.txt", EXPECTED_LOGGED_OUTPUT, null, EXPECTED_RESPONSE_STRING_HEAD);
    }

    @Test
    @Tag("longTest")
    @ResourceLock("TestContext")
    public void testOutputterForPost() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "{ " +
            "\"sourceRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"Content-Type\": [ \"application/json\" ], " +
            "    \"Content-Length\": [ \"652\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"POST\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedJsonBody\": { " +
            "            \"settings\": { " +
            "                \"index\": { " +
            "                    \"number_of_shards\": 7, " +
            "                    \"number_of_replicas\": 3 " +
            "                }, " +
            "                \"analysis\": { " +
            "                    \"analyzer\": { " +
            "                        \"nameAnalyzer\": { " +
            "                            \"type\": \"custom\", " +
            "                            \"tokenizer\": \"keyword\", " +
            "                            \"filter\": \"uppercase\" " +
            "                        } " +
            "                    } " +
            "                } " +
            "            }, " +
            "            \"mappings\": { " +
            "                \"employee\": { " +
            "                    \"properties\": { " +
            "                        \"age\": { " +
            "                            \"type\": \"long\" " +
            "                        }, " +
            "                        \"level\": { " +
            "                            \"type\": \"long\" " +
            "                        }, " +
            "                        \"title\": { " +
            "                            \"type\": \"text\" " +
            "                        }, " +
            "                        \"name\": { " +
            "                            \"type\": \"text\", " +
            "                            \"analyzer\": \"nameAnalyzer\" " +
            "                        } " +
            "                    } " +
            "                } " +
            "            } " +
            "        } " +
            "    } " +
            "}, " +
            "\"sourceResponse\": { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 0, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"I should be decrypted tester!\\r\" " +
            "    } " +
            "}, " +
            "\"targetRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"Content-Type\": [ \"application/json\" ], " +
            "    \"Content-Length\": [ \"652\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"POST\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedJsonBody\": { " +
            "            \"settings\": { " +
            "                \"index\": { " +
            "                    \"number_of_shards\": 7, " +
            "                    \"number_of_replicas\": 3 " +
            "                }, " +
            "                \"analysis\": { " +
            "                    \"analyzer\": { " +
            "                        \"nameAnalyzer\": { " +
            "                            \"type\": \"custom\", " +
            "                            \"tokenizer\": \"keyword\", " +
            "                            \"filter\": \"uppercase\" " +
            "                        } " +
            "                    } " +
            "                } " +
            "            }, " +
            "            \"mappings\": { " +
            "                \"employee\": { " +
            "                    \"properties\": { " +
            "                        \"age\": { " +
            "                            \"type\": \"long\" " +
            "                        }, " +
            "                        \"level\": { " +
            "                            \"type\": \"long\" " +
            "                        }, " +
            "                        \"title\": { " +
            "                            \"type\": \"text\" " +
            "                        }, " +
            "                        \"name\": { " +
            "                            \"type\": \"text\", " +
            "                            \"analyzer\": \"nameAnalyzer\" " +
            "                        } " +
            "                    } " +
            "                } " +
            "            } " +
            "        } " +
            "    } " +
            "}, " +
            "\"targetResponses\": [ { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 267, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"I should be decrypted tester!\\r\" " +
            "    } " +
            "} ], " +
            "\"connectionId\": \"testConnection.1\", " +
            "\"numRequests\": 1, " +
            "\"numErrors\": 0 " +
            "}";
        testOutputterForRequest("post_formUrlEncoded_withFixedLength.txt", EXPECTED_LOGGED_OUTPUT, null);
    }

    private void testOutputterForRequest(String requestResourceName, String expected, IJsonTransformer transformer) throws IOException {
        testOutputterForRequestWithTransformerSupplier(requestResourceName, expected, transformer != null ? () -> transformer : null, null);
    }

    private void testOutputterForRequestWithTransformerSupplier(String requestResourceName, String expected, java.util.function.Supplier<IJsonTransformer> transformerSupplier, String responseOverride) throws IOException {
        var trafficStreamKey = PojoTrafficStreamKeyAndContext.build(
            NODE_ID,
            "c",
            0,
            rootContext::createTrafficStreamContextForTest
        );
        var sourcePair = new RequestResponsePacketPair(trafficStreamKey, Instant.EPOCH, 0, 0);
        var rawRequestData = loadResourceAsBytes("/requests/raw/" + requestResourceName);
        sourcePair.addRequestData(Instant.EPOCH, rawRequestData);
        var rawResponseData = (responseOverride != null ? responseOverride : EXPECTED_RESPONSE_STRING).getBytes(StandardCharsets.UTF_8);
        sourcePair.addResponseData(Instant.EPOCH, rawResponseData);

        var targetRequest = new ByteBufList();
        targetRequest.add(Unpooled.wrappedBuffer(rawRequestData));
        var targetResponse = new ArrayList<AbstractMap.SimpleEntry<Instant, byte[]>>();
        targetResponse.add(new AbstractMap.SimpleEntry<>(Instant.now(), rawResponseData));
        var aggregatedResponse = new AggregatedRawResponse(null, 13, Duration.ofMillis(267), targetResponse, null);
        var targetResponses = new TransformedTargetRequestAndResponseList(
            targetRequest,
            HttpRequestTransformationStatus.skipped(),
            aggregatedResponse
        );
        try (var tupleContext = rootContext.getTestTupleContext(); var closeableLogSetup = new CloseableLogSetup(calculateLoggerName(this.getClass()))) {
            var tuple = new SourceTargetCaptureTuple(
                tupleContext,
                sourcePair,
                targetResponses,
                null
            );
            var streamConsumer = new ResultsToLogsConsumer(closeableLogSetup.getTestLogger(), null, transformerSupplier);
            var consumer = new TupleParserChainConsumer(streamConsumer);
            consumer.accept(tuple);
            Assertions.assertEquals(1, closeableLogSetup.getLogEvents().size());
            var contents = closeableLogSetup.getLogEvents().get(0);
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
        targetRequest.release();
    }

    static String normalizeJson(String input) throws JsonProcessingException {
        return mapper.writeValueAsString(mapper.readTree(input));
    }

    @Test
    @ResourceLock("TestContext")
    public void testTransformerWithJsonJoltTransformer() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "{" +
            "\"sourceRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"auTHorization\": \"REDACTED\", " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"GET\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"sourceResponse\": { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 0, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"REDACTED\" " +
            "    } " +
            "}, " +
            "\"targetRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"auTHorization\": \"REDACTED\", " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"GET\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"\" " +
            "    } " +
            "}, " +
            "\"targetResponses\": [ { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 267, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"REDACTED\" " +
            "    } " +
            "} ], " +
            "\"connectionId\": \"testConnection.1\", " +
            "\"numRequests\": 1, " +
            "\"numErrors\": 0 " +
            "}";

        String joltSpec = "{ " +
            "    \"operation\": \"modify-overwrite-beta\", " +
            "    \"spec\": { " +
            "      \"sourceRequest\": { " +
            "        \"auTHorization\": \"REDACTED\" " +
            "      }, " +
            "      \"sourceResponse\": { " +
            "        \"payload\": { " +
            "          \"inlinedTextBody\": \"REDACTED\" " +
            "        } " +
            "      }, " +
            "      \"targetRequest\": { " +
            "        \"auTHorization\": \"REDACTED\" " +
            "      }, " +
            "      \"targetResponses\": { " +
            "        \"*\": { " +
            "          \"payload\": { " +
            "            \"inlinedTextBody\": \"REDACTED\" " +
            "          } " +
            "        } " +
            "      } " +
            "   } " +
            "}";
        String fullConfig = "[{\"JsonJoltTransformerProvider\": { \"script\": " + joltSpec + "}}]";
        IJsonTransformer jsonJoltTransformer = new TransformationLoader().getTransformerFactoryLoader(fullConfig);
        testOutputterForRequest("get_withAuthHeader.txt", EXPECTED_LOGGED_OUTPUT, jsonJoltTransformer);
    }

    @Test
    @Tag("longTest")
    @ResourceLock("TestContext")
    public void testOutputterForGzip() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "{" +
            "\"sourceRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"Authorization\": [ \"Basic YWRtaW46YWRtaW4=\" ], " +
            "    \"Content-Type\": [ \"application/json\" ], " +
            "    \"transfer-encoding\": [ \"chunked\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"POST\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedJsonBody\": { " +
            "            \"name\": \"John\", " +
            "            \"age\": 30, " +
            "            \"city\": \"Austin\" " +
            "        } " +
            "    } " +
            "}, " +
            "\"sourceResponse\": { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 0, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"I should be decrypted tester!\\r\" " +
            "    } " +
            "}, " +
            "\"targetRequest\": { " +
            "    \"Host\": [ \"foo.example\" ], " +
            "    \"Authorization\": [ \"Basic YWRtaW46YWRtaW4=\" ], " +
            "    \"Content-Type\": [ \"application/json\" ], " +
            "    \"transfer-encoding\": [ \"chunked\" ], " +
            "    \"Request-URI\": \"/test\", " +
            "    \"Method\": \"POST\", " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"payload\": { " +
            "        \"inlinedJsonBody\": { " +
            "            \"name\": \"John\", " +
            "            \"age\": 30, " +
            "            \"city\": \"Austin\" " +
            "        } " +
            "    } " +
            "}, " +
            "\"targetResponses\": [ { " +
            "    \"Content-transfer-encoding\": [ \"chunked\" ], " +
            "    \"Date\": [ \"Thu, 08 Jun 2023 23:06:23 GMT\" ], " +
            "    \"Transfer-encoding\": [ \"chunked\" ], " +
            "    \"Content-type\": [ \"text/plain\" ], " +
            "    \"Funtime\": [ \"checkIt!\" ], " +
            "    \"HTTP-Version\": \"HTTP/1.1\", " +
            "    \"Status-Code\": 200, " +
            "    \"Reason-Phrase\": \"OK\", " +
            "    \"response_time_ms\": 267, " +
            "    \"payload\": { " +
            "        \"inlinedTextBody\": \"I should be decrypted tester!\\r\" " +
            "    } " +
            "} ], " +
            "\"connectionId\": \"testConnection.1\", " +
            "\"numRequests\": 1, " +
            "\"numErrors\": 0 " +
            "}";
        testOutputterForRequest("post_json_gzip.gz", EXPECTED_LOGGED_OUTPUT, null);
    }

    @Test
    @Tag("longTest")
    @ResourceLock("TestContext")
    public void testOutputterForGzipWithTransformer() throws IOException {
        final String EXPECTED_LOGGED_OUTPUT = "{" +
            "\"sourceRequestName\": \"John\", " +
            "\"targetRequestName\": \"John\" " +
            "}";
            String joltSpec = "{ " +
            "    \"operation\": \"shift\", " +
            "    \"spec\": { " +
            "      \"sourceRequest\": { " +
            "        \"payload\": { " +
            "          \"inlinedJsonBody\": { " +
            "            \"name\": \"sourceRequestName\" " +
            "          } " +
            "        } " +
            "      }, " +
            "      \"targetRequest\": { " +
            "        \"payload\": { " +
            "          \"inlinedJsonBody\": { " +
            "            \"name\": \"targetRequestName\" " +
            "          } " +
            "        } " +
            "      } " +
            "   } " +
            "}";
        String fullConfig = "[{\"JsonJoltTransformerProvider\": { \"script\": " + joltSpec + "}}]";
        IJsonTransformer jsonJoltTransformer = new TransformationLoader().getTransformerFactoryLoader(fullConfig);
        testOutputterForRequest("post_json_gzip.gz", EXPECTED_LOGGED_OUTPUT, jsonJoltTransformer);
    }

}
