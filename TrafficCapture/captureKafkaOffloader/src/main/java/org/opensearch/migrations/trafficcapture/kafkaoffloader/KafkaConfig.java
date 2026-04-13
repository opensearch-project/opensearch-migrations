package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

import com.beust.jcommander.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;

@Slf4j
public class KafkaConfig {
    public static final String DEFAULT_KAFKA_CLIENT_ID = "HttpCaptureProxyProducer";
    public static final String AUTH_TYPE_NONE = "none";
    public static final String AUTH_TYPE_MSK_IAM = "msk-iam";
    public static final String AUTH_TYPE_SCRAM_SHA_512 = "scram-sha-512";

    private KafkaConfig() {
        throw new IllegalStateException("Utility class should not be instantiated");
    }

    public static class KafkaParameters {
        @Parameter(required = false,
                names = { "--kafkaPropertyFile", "--kafkaConfigFile" },
                arity = 1,
                description = "Kafka properties file for additional client customization.")
        public String kafkaPropertyFile;
        @Parameter(required = false,
                names = { "--kafkaClientId" },
                arity = 1,
                description = "clientId to use for interfacing with Kafka.")
        public String kafkaClientId = DEFAULT_KAFKA_CLIENT_ID;
        @Parameter(required = false,
                names = { "--kafkaBrokers", "--kafkaConnection" },
                arity = 1,
                description = "Comma-separated list of Kafka bootstrap brokers as <HOSTNAME:PORT> values.")
        public String kafkaBrokers;
        @Parameter(required = false,
                names = { "--enableMSKAuth" },
                arity = 0,
                description = "Legacy flag that enables MSK IAM auth. Prefer --kafkaAuthType=msk-iam.")
        public Boolean legacyEnableMSKAuth;
        @Parameter(required = false,
                names = { "--kafkaAuthType" },
                arity = 1,
                description = "Kafka client auth mode. Supported values: none, msk-iam, scram-sha-512.")
        public String kafkaAuthType;
        @Parameter(required = false,
                names = { "--kafkaListenerName" },
                arity = 1,
                description = "Kafka listener name selected by orchestration.")
        public String kafkaListenerName;
        @Parameter(required = false,
                names = { "--kafkaSecretName" },
                arity = 1,
                description = "Kubernetes Secret containing Kafka client auth material.")
        public String kafkaSecretName;
        @Parameter(required = false,
                names = { "--kafkaUserName" },
                arity = 1,
                description = "Kafka user/principal name selected by orchestration.")
        public String kafkaUserName;
        @Parameter(required = false,
                names = { "--kafkaPassword" },
                arity = 1,
                description = "Kafka password for SCRAM auth. Prefer setting via CAPTURE_PROXY_KAFKA_PASSWORD env var.")
        public String kafkaPassword;

        public boolean isMskAuthEnabled() {
            validateKafkaAuthFlags();
            return AUTH_TYPE_MSK_IAM.equals(getEffectiveKafkaAuthType());
        }

        public String getEffectiveKafkaAuthType() {
            validateKafkaAuthFlags();
            if (kafkaAuthType != null && !kafkaAuthType.isBlank()) {
                return kafkaAuthType;
            }
            return Boolean.TRUE.equals(legacyEnableMSKAuth) ? AUTH_TYPE_MSK_IAM : AUTH_TYPE_NONE;
        }

        public void validateKafkaAuthFlags() {
            if (kafkaAuthType != null && !kafkaAuthType.isBlank()) {
                if (Boolean.TRUE.equals(legacyEnableMSKAuth) && !AUTH_TYPE_MSK_IAM.equals(kafkaAuthType)) {
                    throw new IllegalArgumentException(
                            "--enableMSKAuth is only compatible with --kafkaAuthType=msk-iam"
                    );
                }
                if (!AUTH_TYPE_NONE.equals(kafkaAuthType)
                        && !AUTH_TYPE_MSK_IAM.equals(kafkaAuthType)
                        && !AUTH_TYPE_SCRAM_SHA_512.equals(kafkaAuthType)) {
                    throw new IllegalArgumentException(
                            "Unsupported --kafkaAuthType value: " + kafkaAuthType
                    );
                }
            }
        }
    }

    public static Properties buildKafkaProperties(KafkaParameters params) throws IOException {
        return buildKafkaProperties(params.kafkaPropertyFile, params.kafkaBrokers, params.kafkaClientId,
                params.getEffectiveKafkaAuthType(), params.kafkaUserName, params.kafkaPassword);
    }

    public static Properties buildKafkaProperties(String kafkaPropertiesFile, String kafkaConnection, String kafkaClientId,
                                                  String authType, String kafkaUserName, String kafkaPassword) throws IOException {
        var kafkaProps = new Properties();
        kafkaProps.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringSerializer"
        );
        kafkaProps.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArraySerializer"
        );
        // Property details:
        // https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#delivery-timeout-ms
        kafkaProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        kafkaProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);

        if (kafkaPropertiesFile != null) {
            try (var fileReader = new FileReader(kafkaPropertiesFile)) {
                kafkaProps.load(fileReader);
            } catch (IOException e) {
                log.error(
                        "Unable to locate provided Kafka producer properties file path: " + kafkaPropertiesFile
                );
                throw e;
            }
        }

        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection);
        kafkaProps.put(ProducerConfig.CLIENT_ID_CONFIG, kafkaClientId);
        applySaslAuthProperties(kafkaProps, authType, kafkaUserName, kafkaPassword);
        return kafkaProps;
    }

    static void applySaslAuthProperties(Properties props, String authType, String kafkaUserName, String kafkaPassword) {
        if (AUTH_TYPE_MSK_IAM.equals(authType)) {
            props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.setProperty(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
            props.setProperty(SaslConfigs.SASL_JAAS_CONFIG,
                    "software.amazon.msk.auth.iam.IAMLoginModule required;");
            props.setProperty(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        } else if (AUTH_TYPE_SCRAM_SHA_512.equals(authType)) {
            props.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            props.setProperty(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512");
            if (kafkaUserName != null && kafkaPassword != null) {
                props.setProperty(SaslConfigs.SASL_JAAS_CONFIG,
                        "org.apache.kafka.common.security.scram.ScramLoginModule required "
                                + "username=\"" + kafkaUserName + "\" "
                                + "password=\"" + kafkaPassword + "\";");
            }
        }
    }
}
