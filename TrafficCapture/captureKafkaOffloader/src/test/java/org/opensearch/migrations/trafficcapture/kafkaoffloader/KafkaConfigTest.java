package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.Properties;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class KafkaConfigTest {

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
}
