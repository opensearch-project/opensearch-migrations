package org.opensearch.migrations.replay;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class TrafficReplayerKafkaParameterTest {

    @Test
    public void testNormalizedKafkaAliasesPopulateLegacyFields() throws Exception {
        var parseArgs = TrafficReplayer.class.getDeclaredMethod("parseArgs", String[].class);
        parseArgs.setAccessible(true);

        var parameters = (TrafficReplayer.Parameters) parseArgs.invoke(
            null,
            (Object) new String[] {
                "--target-uri", "http://localhost:9200",
                "--kafkaBrokers", "broker:9092",
                "--kafkaTopic", "traffic-topic",
                "--kafkaGroupId", "replayer-group",
                "--kafkaPropertyFile", "/tmp/client.properties",
                "--kafkaAuthType", "msk-iam",
                "--kafkaListenerName", "plain",
                "--kafkaSecretName", "traffic-secret",
                "--kafkaUserName", "traffic-user"
            }
        );

        Assertions.assertEquals("broker:9092", parameters.kafkaTrafficBrokers);
        Assertions.assertEquals("traffic-topic", parameters.kafkaTrafficTopic);
        Assertions.assertEquals("replayer-group", parameters.kafkaTrafficGroupId);
        Assertions.assertEquals("/tmp/client.properties", parameters.kafkaTrafficPropertyFile);
        Assertions.assertEquals("msk-iam", parameters.kafkaTrafficAuthType);
        Assertions.assertEquals("plain", parameters.kafkaTrafficListenerName);
        Assertions.assertEquals("traffic-secret", parameters.kafkaTrafficSecretName);
        Assertions.assertEquals("traffic-user", parameters.kafkaTrafficUserName);
        Assertions.assertTrue(parameters.isKafkaTrafficEnableMSKAuth());
    }

    @Test
    public void testLegacyAndNormalizedAuthFlagsMustAgree() {
        var parameters = new TrafficReplayer.Parameters();
        parameters.kafkaTrafficEnableMSKAuth = true;
        parameters.kafkaTrafficAuthType = "scram-sha-512";

        Assertions.assertThrows(com.beust.jcommander.ParameterException.class, parameters::validateKafkaAuthFlags);
    }

    @Test
    void everyRetiredCliAliasParsesWarnsAndRemainsACompatibilityOnlyValue() {
        assertRetiredOption("--lookahead-time-window", "17", "lookahead", params -> params.lookaheadTimeSeconds);
        assertRetiredOption("--lookaheadTimeWindow", "17", "lookahead", params -> params.lookaheadTimeSeconds);
        assertRetiredOption("--lookaheadTimeSeconds", "17", "lookahead", params -> params.lookaheadTimeSeconds);
        assertRetiredOption("--quiescent-period-ms", "17", "quiescent", params -> params.quiescentPeriodMs);
        assertRetiredOption("--quiescentPeriodMs", "17", "quiescent", params -> params.quiescentPeriodMs);
        assertRetiredOption("--packet-timeout-seconds", "17", "packet",
            params -> params.observedPacketConnectionTimeout);
        assertRetiredOption("--packetTimeoutSeconds", "17", "packet",
            params -> params.observedPacketConnectionTimeout);
        assertRetiredOption("--observedPacketConnectionTimeout", "17", "packet",
            params -> params.observedPacketConnectionTimeout);
    }

    @Test
    void retiredInlineJsonKeysParseAndWarnWithoutAffectingLiveConfiguration() {
        var parameters = new TrafficReplayer.Parameters();
        var parser = JsonCommandLineParser.newBuilder().addObject(parameters).build();

        parser.parse(new String[] {
            "---INLINE-JSON",
            """
            {
              "lookaheadTimeSeconds": 11,
              "quiescentPeriodMs": 12,
              "observedPacketConnectionTimeout": 13
            }
            """
        });

        var warnings = parameters.validateAndCollectCompatibilityWarnings();
        Assertions.assertEquals(3, warnings.size());
        Assertions.assertEquals(TrafficReplayer.DEFAULT_MAX_CONCURRENT_TARGET_ATTEMPTS,
            parameters.getEffectiveMaxConcurrentTargetAttempts());
        Assertions.assertNull(parameters.numClientThreads);
    }

    @Test
    void removedShortPacketTimeoutAliasFailsAsUnknown() {
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            () -> parseParameters("-t", "17")
        );
    }

    @Test
    void removedConfigurationNamesAndUnknownInlineJsonFailImmediately() {
        for (var removedCliName : new String[] {
            "--max-owned-kafka-records",
            "--max-owned-kafka-bytes",
            "--disable-liveness-scanner",
            "--timestamp-mode",
            "--rebase-without-expiration"
        }) {
            Assertions.assertThrows(
                com.beust.jcommander.ParameterException.class,
                () -> parseParameters(removedCliName, "1"),
                removedCliName
            );
        }

        for (var removedJsonName : new String[] {
            "maxOwnedKafkaRecords",
            "maxOwnedKafkaBytes",
            "disableLivenessScanner",
            "timestampMode",
            "rebaseWithoutExpiration",
            "unknownReplayerSetting"
        }) {
            var failure = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parseParameters(
                    "---INLINE-JSON",
                    "{\"" + removedJsonName + "\":1}"
                ),
                removedJsonName
            );
            Assertions.assertTrue(
                failure.getMessage().contains(removedJsonName),
                failure::getMessage
            );
        }
    }

    @Test
    void preferredAndDeprecatedTargetAttemptNamesMapIdenticallyAndConflictsFail() {
        var preferred = parseParameters("--max-concurrent-target-attempts", "7");
        var legacy = parseParameters("--maxConcurrentRequests", "7");

        Assertions.assertEquals(7, preferred.getEffectiveMaxConcurrentTargetAttempts());
        Assertions.assertEquals(7, legacy.getEffectiveMaxConcurrentTargetAttempts());
        Assertions.assertEquals(1, legacy.validateAndCollectCompatibilityWarnings().size());

        var conflict = parseParameters(
            "--maxConcurrentTargetAttempts",
            "7",
            "--max-concurrent-requests",
            "8"
        );
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            conflict::validateAndCollectCompatibilityWarnings
        );
    }

    @Test
    void invalidKnownStartupValuesAreRejected() {
        var zeroThreads = new TrafficReplayer.Parameters();
        zeroThreads.numClientThreads = 0;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            zeroThreads::validateAndCollectCompatibilityWarnings
        );

        var zeroAttempts = new TrafficReplayer.Parameters();
        zeroAttempts.maxConcurrentTargetAttempts = 0;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            zeroAttempts::validateAndCollectCompatibilityWarnings
        );

        var zeroSpeed = new TrafficReplayer.Parameters();
        zeroSpeed.speedupFactor = 0;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            zeroSpeed::validateAndCollectCompatibilityWarnings
        );

        var negativeCancellationGrace = new TrafficReplayer.Parameters();
        negativeCancellationGrace.cancellationGraceMs = -1;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            negativeCancellationGrace::validateAndCollectCompatibilityWarnings
        );

        var zeroHeartbeatExpiration = new TrafficReplayer.Parameters();
        zeroHeartbeatExpiration.heartbeatExpirationIntervalSeconds = 0;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            zeroHeartbeatExpiration::validateAndCollectCompatibilityWarnings
        );

        var negativeMaximumSkew = new TrafficReplayer.Parameters();
        negativeMaximumSkew.maximumBackwardSkewSeconds = -1;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            negativeMaximumSkew::validateAndCollectCompatibilityWarnings
        );

        var zeroSourceResponseWindow = new TrafficReplayer.Parameters();
        zeroSourceResponseWindow.sourceResponseRetryWindowSeconds = 0;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            zeroSourceResponseWindow::validateAndCollectCompatibilityWarnings
        );

        var zeroReadyRequestsBuffer = new TrafficReplayer.Parameters();
        zeroReadyRequestsBuffer.readyRequestsBufferPerThread = 0;
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            zeroReadyRequestsBuffer::validateAndCollectCompatibilityWarnings
        );
    }

    @Test
    void brokerTimeConfigurationDefaultsUseTheConservativeFiveSecondSkew() {
        var parameters = new TrafficReplayer.Parameters();
        var configuration = TrafficReplayer.brokerTimeConfiguration(parameters);

        Assertions.assertEquals(
            TrafficReplayer.DEFAULT_MAXIMUM_BACKWARD_SKEW_SECONDS,
            parameters.maximumBackwardSkewSeconds
        );
        Assertions.assertEquals(5, parameters.maximumBackwardSkewSeconds);
        Assertions.assertEquals(30_000, configuration.heartbeatExpirationMillis());
        Assertions.assertEquals(5_000, configuration.maximumBackwardSkewMillis());
        Assertions.assertEquals(5_000, configuration.sourceResponseRetryWindowMillis());
    }

    @Test
    void brokerTimeAndReadyBufferOptionsSupportCliAliasesAndInlineJson() {
        var kebab = parseParameters(
            "--heartbeat-expiration-interval-seconds", "31",
            "--maximum-backward-skew-seconds", "6",
            "--source-response-retry-window-seconds", "7",
            "--ready-requests-buffer-per-thread", "3"
        );
        Assertions.assertEquals(31, kebab.heartbeatExpirationIntervalSeconds);
        Assertions.assertEquals(6, kebab.maximumBackwardSkewSeconds);
        Assertions.assertEquals(7, kebab.sourceResponseRetryWindowSeconds);
        Assertions.assertEquals(3, kebab.readyRequestsBufferPerThread);

        var camel = parseParameters(
            "--heartbeatExpirationIntervalSeconds", "32",
            "--maximumBackwardSkewSeconds", "8",
            "--sourceResponseRetryWindowSeconds", "9",
            "--readyRequestsBufferPerThread", "4"
        );
        Assertions.assertEquals(32, camel.heartbeatExpirationIntervalSeconds);
        Assertions.assertEquals(8, camel.maximumBackwardSkewSeconds);
        Assertions.assertEquals(9, camel.sourceResponseRetryWindowSeconds);
        Assertions.assertEquals(4, camel.readyRequestsBufferPerThread);

        var inline = parseParameters(
            "---INLINE-JSON",
            """
            {
              "heartbeatExpirationIntervalSeconds": 33,
              "maximumBackwardSkewSeconds": 10,
              "sourceResponseRetryWindowSeconds": 11,
              "readyRequestsBufferPerThread": 5
            }
            """
        );
        Assertions.assertEquals(33, inline.heartbeatExpirationIntervalSeconds);
        Assertions.assertEquals(10, inline.maximumBackwardSkewSeconds);
        Assertions.assertEquals(11, inline.sourceResponseRetryWindowSeconds);
        Assertions.assertEquals(5, inline.readyRequestsBufferPerThread);
    }

    @Test
    void replayModeRequiresTargetAndCompleteKafkaConfiguration() {
        var parameters = new TrafficReplayer.Parameters();
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            () -> TrafficReplayer.validateReplayModeParams(parameters)
        );

        parameters.targetUriString = "http://localhost:9200";
        parameters.kafkaTrafficBrokers = "broker:9092";
        parameters.kafkaTrafficTopic = "traffic";
        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            () -> TrafficReplayer.validateReplayModeParams(parameters)
        );

        parameters.kafkaTrafficGroupId = "replayer";
        Assertions.assertDoesNotThrow(
            () -> TrafficReplayer.validateReplayModeParams(parameters)
        );
    }

    @Test
    void unknownModeFailsAtTheFirstConfigurationValidationBoundary() {
        var parameters = new TrafficReplayer.Parameters();
        parameters.mode = "surprise";

        Assertions.assertThrows(
            com.beust.jcommander.ParameterException.class,
            parameters::validateAndCollectCompatibilityWarnings
        );
    }

    @Test
    void cancellationGraceUsesTheRequiredAliasesAndDefault() {
        Assertions.assertEquals(1_000, parseParameters().cancellationGraceMs);
        Assertions.assertEquals(
            25,
            parseParameters("--cancellation-grace-ms", "25").cancellationGraceMs
        );
        Assertions.assertEquals(
            30,
            parseParameters("--cancellationGraceMs", "30").cancellationGraceMs
        );
    }

    @Test
    void deployedS3SinkFactoryUsesStableWorkerIndexAndClosesOwnedResources() throws Exception {
        var putRequest = new AtomicReference<PutObjectRequest>();
        var clientClosed = new AtomicBoolean();
        var s3Client = (S3AsyncClient) Proxy.newProxyInstance(
            S3AsyncClient.class.getClassLoader(),
            new Class<?>[] { S3AsyncClient.class },
            (proxy, method, arguments) -> {
                if (method.getName().equals("putObject")) {
                    putRequest.set((PutObjectRequest) arguments[0]);
                    return CompletableFuture.completedFuture(PutObjectResponse.builder().build());
                }
                if (method.getName().equals("close")) {
                    clientClosed.set(true);
                    return null;
                }
                if (method.getName().equals("serviceName")) {
                    return "s3";
                }
                if (method.getName().equals("toString")) {
                    return "capturing-s3-client";
                }
                return null;
            }
        );
        var parameters = new TrafficReplayer.Parameters();
        parameters.tupleS3Bucket = "tuple-bucket";
        parameters.tupleS3Prefix = "configured-prefix/";
        parameters.tupleMaxFileSizeMb = 1;
        parameters.tupleMaxBufferSeconds = 60;
        parameters.tupleMaxPerFile = 1;

        try (var resources = TrafficReplayer.createS3TupleSinkResources(parameters, s3Client);
             var sink = resources.factory().create(7)) {
            sink.write(null, Map.of("connectionId", "connection-1")).toCompletableFuture().join();
        }

        Assertions.assertNotNull(putRequest.get());
        Assertions.assertEquals("tuple-bucket", putRequest.get().bucket());
        Assertions.assertTrue(
            putRequest.get().key().contains("/tuples-7-"),
            () -> "stable worker index absent from key " + putRequest.get().key()
        );
        Assertions.assertTrue(clientClosed.get());
    }

    @Test
    void poisonExceptionTypesAreExplicitAndNormalizedByTheSharedAllowlist() throws Exception {
        var parseArgs = TrafficReplayer.class.getDeclaredMethod("parseArgs", String[].class);
        parseArgs.setAccessible(true);

        var parameters = (TrafficReplayer.Parameters) parseArgs.invoke(
            null,
            (Object) new String[] {
                "--target-uri", "http://localhost:9200",
                "--poison-doc-exception-types", " Mapper_Parsing_Exception ,version_conflict_engine_exception"
            }
        );
        var allowlist = new ExceptionTypeAllowlist(parameters.poisonDocExceptionTypes);

        Assertions.assertTrue(allowlist.isAllowed("mapper_parsing_exception"));
        Assertions.assertTrue(allowlist.isAllowed("VERSION_CONFLICT_ENGINE_EXCEPTION"));
        Assertions.assertFalse(allowlist.isAllowed("illegal_argument_exception"));
    }

    private static void assertRetiredOption(
        String option,
        String value,
        String warningFragment,
        java.util.function.Function<TrafficReplayer.Parameters, Number> parsedValue
    ) {
        var parameters = parseParameters(option, value);
        var warnings = parameters.validateAndCollectCompatibilityWarnings();

        Assertions.assertEquals(17, parsedValue.apply(parameters).intValue());
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertTrue(warnings.get(0).contains(warningFragment));
        Assertions.assertEquals(
            TrafficReplayer.DEFAULT_MAX_CONCURRENT_TARGET_ATTEMPTS,
            parameters.getEffectiveMaxConcurrentTargetAttempts()
        );
        Assertions.assertNull(parameters.numClientThreads);
    }

    private static TrafficReplayer.Parameters parseParameters(String... args) {
        var parameters = new TrafficReplayer.Parameters();
        JsonCommandLineParser.newBuilder().addObject(parameters).build().parse(args);
        return parameters;
    }
}
