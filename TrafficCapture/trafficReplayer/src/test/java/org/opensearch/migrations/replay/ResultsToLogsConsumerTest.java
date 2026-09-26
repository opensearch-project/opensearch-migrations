package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;

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
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// REBUILD-LIMBO(G10) -- inherited test bodies remain marked and recoverable; the live replacement
// follows the marked regions. Javadoc stays outside the regions and keeps its blame.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest JsonProcessingException ObjectMapper PojoTrafficStreamKeyAndContext TestContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.DiagnosticPayload;
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
            new DiagnosticPayload(targetRequest),
            HttpRequestTransformationStatus.skipped(),
            aggregatedResponse
        );
        try (var tupleContext = rootContext.getTestTupleContext(); var closeableLogSetup = new CloseableLogSetup(calculateLoggerName(this.getClass()))) {
            try (var tuple = new SourceTargetCaptureTuple(
                    tupleContext,
                    sourcePair,
                    targetResponses,
                    null
                )) {
                var streamConsumer = new ResultsToLogsConsumer(closeableLogSetup.getTestLogger(), null, transformerSupplier);
                var consumer = new TupleParserChainConsumer(streamConsumer);
                consumer.accept(tuple);
                Assertions.assertEquals(1, closeableLogSetup.getLogEvents().size());
                var contents = closeableLogSetup.getLogEvents().get(0);
                log.info("Output=" + contents);
                Assertions.assertEquals(normalizeJson(expected), normalizeJson(contents));
            }
        }
        var allMetricData = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        var filteredMetrics = allMetricData.stream()
            .filter(md -> md.getName().startsWith("tupleResult"))
            .collect(Collectors.toList());
        // TODO - find out how to verify these metrics
        log.error("TODO - find out how to verify these metrics");
        // Assertions.assertEquals("REQUEST_ID:testConnection.1|SOURCE_HTTP_STATUS:200|TARGET_HTTP_STATUS:200|HTTP_STATUS_MATCH:1",
        // filteredMetrics.stream().map(md->md.getName()+":"+md.getData()).collect(Collectors.joining("|")));
        Assertions.assertTrue(targetRequest.isClosed());
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

*/
// REBUILD-LIMBO-END(G10)

class ResultsToLogsConsumerTest {
    @Test
    void progressSummaryAndTransformedTupleRemainSeparateLoggerStreams() {
        try (
            var tupleLogs = new CloseableLogSetup("g9-output-tuple");
            var progressLogs = new CloseableLogSetup("g9-progress-summary");
            var fixture = new ResultFixture()
        ) {
            var output = new ResultsToLogsConsumer(
                tupleLogs.getTestLogger(),
                progressLogs.getTestLogger()
            );

            var tuple = output.createTupleAndReportProgress(
                fixture.tupleContext,
                fixture.result
            );
            try (var sink = output.tupleSinkFactory().create(0)) {
                sink.write(fixture.tupleContext, tuple)
                    .toCompletableFuture()
                    .join();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }

            Assertions.assertEquals(1, progressLogs.getLogEvents().size());
            var progress = progressLogs.getLogEvents().getFirst();
            Assertions.assertTrue(progress.startsWith("0, connection.2, "));
            Assertions.assertTrue(progress.endsWith(", GET, /test"));
            Assertions.assertEquals(1, tupleLogs.getLogEvents().size());
            var json = tupleLogs.getLogEvents().getFirst();
            Assertions.assertTrue(json.contains("\"sourceRequest\""));
            Assertions.assertTrue(json.contains("\"targetResponses\""));
            Assertions.assertFalse(
                tuple.containsKey("progressSummary"),
                "progress bookkeeping must not enter tuple or S3 output"
            );
        }
    }

    private static final class ResultFixture implements AutoCloseable {
        private static final TopicPartition PARTITION =
            new TopicPartition("traffic", 0);
        private final PartitionGenerationId generation =
            new PartitionGenerationId(PARTITION, 1);
        private final ConnectionProcessingId connection =
            new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("writer", "connection"),
                0
            );
        private final ReplayRequestId requestId =
            new ReplayRequestId(connection, 2);
        private final RootReplayerContext root =
            new RootReplayerContext(OpenTelemetry.noop());
        private final IReplayContexts.IKafkaRecordContext recordContext =
            root.createKafkaRecordContext(new KafkaRecordId(generation, 1), 0);
        private final IReplayContexts.ITrafficStreamsLifecycleContext trafficContext =
            recordContext.createTrafficStreamContext(1);
        private final IReplayContexts.IRequestContext requestContext =
            trafficContext.createRequestContext(requestId, Instant.EPOCH);
        private final IReplayContexts.ITupleHandlingContext tupleContext;
        private final NettyPacketToHttpConsumer.PreparedRequest preparedRequest;
        private final RequestReplayOwner.RequestResult<
            HttpMessageAndTimestamp.Request,
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            HttpMessageAndTimestamp.Response
        > result;

        private ResultFixture() {
            requestContext.onRequestReconstituted();
            tupleContext = requestContext.createTupleContext();
            var sourceRequest = request(
                "GET /test HTTP/1.1\r\nHost: source\r\n\r\n"
            );
            var targetRequest = Unpooled.copiedBuffer(
                "GET /test HTTP/1.1\r\nHost: target\r\n\r\n",
                StandardCharsets.UTF_8
            );
            var packets = new ByteBufList(targetRequest);
            targetRequest.release();
            preparedRequest = new NettyPacketToHttpConsumer.PreparedRequest(
                ByteBufListProducer.of(packets),
                Duration.ZERO
            );
            var targetResponse = rawResponse(
                200,
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            );
            var terminal =
                new TargetAttemptOutcome.TargetResponseObtained<>(targetResponse);
            result = new RequestReplayOwner.RequestResult<>(
                requestId,
                sourceRequest,
                preparedRequest,
                HttpRequestTransformationStatus.completed(),
                List.of(terminal),
                terminal,
                new RequestReplayOwner.CompleteFinalSourceResponse<>(
                    response(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                    ),
                    true
                )
            );
        }

        @Override
        public void close() {
            preparedRequest.close();
            tupleContext.close();
            requestContext.close();
            trafficContext.close();
            recordContext.complete(
                IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE
            );
        }

        private static HttpMessageAndTimestamp.Request request(String text) {
            var request = new HttpMessageAndTimestamp.Request(Instant.EPOCH);
            request.add(text.getBytes(StandardCharsets.UTF_8));
            request.setLastPacketTimestamp(Instant.EPOCH.plusMillis(1));
            return request;
        }

        private static HttpMessageAndTimestamp.Response response(String text) {
            var response = new HttpMessageAndTimestamp.Response(
                Instant.EPOCH.plusMillis(2)
            );
            response.add(text.getBytes(StandardCharsets.UTF_8));
            response.setLastPacketTimestamp(Instant.EPOCH.plusMillis(3));
            return response;
        }

        private static AggregatedRawResponse rawResponse(
            int status,
            String text
        ) {
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            return new AggregatedRawResponse(
                new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(status)
                ),
                bytes.length,
                Duration.ofMillis(2),
                List.of(new AbstractMap.SimpleEntry<>(
                    Instant.EPOCH.plusMillis(2),
                    bytes
                )),
                null
            );
        }
    }
}
