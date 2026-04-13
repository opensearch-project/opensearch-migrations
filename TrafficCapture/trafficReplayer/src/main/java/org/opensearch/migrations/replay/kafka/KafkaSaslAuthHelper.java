package org.opensearch.migrations.replay.kafka;

import java.util.Properties;

/**
 * Shared SASL auth property configuration for Kafka clients.
 * Duplicated from KafkaConfig.applySaslAuthProperties to avoid a cross-module dependency.
 */
class KafkaSaslAuthHelper {
    static final String AUTH_TYPE_MSK_IAM = "msk-iam";
    static final String AUTH_TYPE_SCRAM_SHA_512 = "scram-sha-512";

    private KafkaSaslAuthHelper() {}

    static void applySaslAuthProperties(Properties props, String authType, String kafkaUserName, String kafkaPassword) {
        if (AUTH_TYPE_MSK_IAM.equals(authType)) {
            props.setProperty("security.protocol", "SASL_SSL");
            props.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            props.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            props.setProperty("sasl.client.callback.handler.class",
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        } else if (AUTH_TYPE_SCRAM_SHA_512.equals(authType)) {
            props.setProperty("security.protocol", "SASL_SSL");
            props.setProperty("sasl.mechanism", "SCRAM-SHA-512");
            if (kafkaUserName != null && kafkaPassword != null) {
                props.setProperty("sasl.jaas.config",
                        "org.apache.kafka.common.security.scram.ScramLoginModule required "
                                + "username=\"" + kafkaUserName + "\" "
                                + "password=\"" + kafkaPassword + "\";");
            }
        }
    }
}
