package org.opensearch.migrations.replay;

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
}
