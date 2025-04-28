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

    private KafkaConfig() {
        throw new IllegalStateException("Utility class should not be instantiated");
    }

    public static class KafkaParameters {
        @Parameter(required = false,
                names = { "--kafkaConfigFile" },
                arity = 1,
                description = "Kafka properties file for additional client customization.")
        public String kafkaPropertiesFile;
        @Parameter(required = false,
                names = { "--kafkaClientId" },
                arity = 1,
                description = "clientId to use for interfacing with Kafka.")
        public String kafkaClientId = DEFAULT_KAFKA_CLIENT_ID;
        @Parameter(required = false,
                names = { "--kafkaConnection" },
                arity = 1,
                description = "Sequence of <HOSTNAME:PORT> values delimited by ','.")
        public String kafkaConnection;
        @Parameter(required = false,
                names = { "--enableMSKAuth" },
                arity = 0,
                description = "Enables SASL Kafka properties required for connecting to MSK with IAM auth.")
        public boolean mskAuthEnabled = false;
    }

    public static Properties buildKafkaProperties(KafkaParameters params) throws IOException {
        return buildKafkaProperties(params.kafkaPropertiesFile, params.kafkaConnection, params.kafkaClientId,
                params.mskAuthEnabled);
    }

    public static Properties buildKafkaProperties(String kafkaPropertiesFile, String kafkaConnection, String kafkaClientId,
                                                  boolean mskAuthEnabled) throws IOException {
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
        if (mskAuthEnabled) {
            kafkaProps.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            kafkaProps.setProperty(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
            kafkaProps.setProperty(
                    SaslConfigs.SASL_JAAS_CONFIG,
                    "software.amazon.msk.auth.iam.IAMLoginModule required;"
            );
            kafkaProps.setProperty(
                    SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
            );
        }
        return kafkaProps;
    }
}
