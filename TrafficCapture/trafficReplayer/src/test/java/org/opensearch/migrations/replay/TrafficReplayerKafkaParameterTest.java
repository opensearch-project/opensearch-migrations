package org.opensearch.migrations.replay;

import org.opensearch.migrations.ExceptionTypeAllowlist;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
    void kafkaDefaultsToEpsilonWhileLegacyInputKeepsItsExistingWindow() {
        var kafkaParameters = new TrafficReplayer.Parameters();
        kafkaParameters.kafkaTrafficBrokers = "broker:9092";
        var legacyParameters = new TrafficReplayer.Parameters();

        Assertions.assertEquals(
            TrafficReplayer.DEFAULT_KAFKA_LOOKAHEAD_SECONDS,
            kafkaParameters.getEffectiveLookaheadTimeSeconds()
        );
        Assertions.assertEquals(
            TrafficReplayer.DEFAULT_LEGACY_LOOKAHEAD_SECONDS,
            legacyParameters.getEffectiveLookaheadTimeSeconds()
        );
    }

    @Test
    void explicitLookaheadOverridesEitherSourceDefault() {
        var parameters = new TrafficReplayer.Parameters();
        parameters.kafkaTrafficBrokers = "broker:9092";
        parameters.lookaheadTimeSeconds = 17;

        Assertions.assertEquals(17, parameters.getEffectiveLookaheadTimeSeconds());
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
}
