package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class KafkaConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void applySaslAuthProperties_SslSetsSecurityProtocol() {
        Properties props = new Properties();
        KafkaConfig.applySaslAuthProperties(props, KafkaConfig.AUTH_TYPE_SSL, null, null);

        assertEquals("SSL", props.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertFalse(props.containsKey(SaslConfigs.SASL_MECHANISM));
        assertFalse(props.containsKey(SaslConfigs.SASL_JAAS_CONFIG));
    }

    @Test
    void applySaslAuthProperties_NoneSetsNothing() {
        Properties props = new Properties();
        KafkaConfig.applySaslAuthProperties(props, KafkaConfig.AUTH_TYPE_NONE, null, null);

        assertFalse(props.containsKey(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
    }

    @Test
    void applySaslAuthProperties_MskIamSetsCorrectProperties() {
        Properties props = new Properties();
        KafkaConfig.applySaslAuthProperties(props, KafkaConfig.AUTH_TYPE_MSK_IAM, null, null);

        assertEquals("SASL_SSL", props.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("AWS_MSK_IAM", props.getProperty(SaslConfigs.SASL_MECHANISM));
    }

    @Test
    void applySaslAuthProperties_ScramSetsCorrectProperties() {
        Properties props = new Properties();
        KafkaConfig.applySaslAuthProperties(props, KafkaConfig.AUTH_TYPE_SCRAM_SHA_512, "user", "pass");

        assertEquals("SASL_SSL", props.getProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SCRAM-SHA-512", props.getProperty(SaslConfigs.SASL_MECHANISM));
        String jaas = props.getProperty(SaslConfigs.SASL_JAAS_CONFIG);
        assertEquals("org.apache.kafka.common.security.scram.ScramLoginModule required "
            + "username=\"user\" password=\"pass\";", jaas);
    }

    @Test
    void validateKafkaAuthFlags_SslIsAccepted() {
        var params = new KafkaConfig.KafkaParameters();
        params.kafkaAuthType = "ssl";
        params.validateKafkaAuthFlags();
        assertEquals("ssl", params.getEffectiveKafkaAuthType());
    }

    @Test
    void validateKafkaAuthFlags_UnsupportedTypeThrows() {
        var params = new KafkaConfig.KafkaParameters();
        params.kafkaAuthType = "kerberos";
        assertThrows(IllegalArgumentException.class, params::validateKafkaAuthFlags);
    }

    @Test
    void producerPropertiesCannotWeakenOrderingSettings() throws IOException {
        var propertyFile = tempDir.resolve("producer.properties");
        Files.writeString(
            propertyFile,
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG
                + "=false\n"
                + ProducerConfig.ACKS_CONFIG
                + "=1\n"
                + ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION
                + "=100\n"
        );

        var properties = KafkaConfig.buildKafkaProperties(
            propertyFile.toString(),
            "broker:9092",
            "client",
            KafkaConfig.AUTH_TYPE_NONE,
            null,
            null
        );

        assertEquals(true, properties.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertEquals("all", properties.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(5, properties.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
    }
}
