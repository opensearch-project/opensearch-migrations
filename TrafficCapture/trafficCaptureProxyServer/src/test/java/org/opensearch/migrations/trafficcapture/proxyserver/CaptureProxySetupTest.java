package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.IOException;
import java.util.Properties;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CaptureProxySetupTest {

    public final static String kafkaBrokerString = "invalid:9092";

    @Test
    public void testBuildKafkaPropertiesBaseCase() throws IOException {
        CaptureProxy.Parameters parameters = CaptureProxy.parseArgs(
            new String[] {
                "--destinationUri",
                "invalid:9200",
                "--listenPort",
                "80",
                "--kafkaConnection",
                kafkaBrokerString }
        );
        Properties props = CaptureProxy.buildKafkaProperties(parameters);

        Assertions.assertEquals(CaptureProxy.DEFAULT_KAFKA_CLIENT_ID, props.get(ProducerConfig.CLIENT_ID_CONFIG));
        Assertions.assertEquals(kafkaBrokerString, props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.StringSerializer",
            props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        );
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArraySerializer",
            props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        );

    }

    @Test
    public void testBuildKafkaPropertiesWithMSKAuth() throws IOException {
        CaptureProxy.Parameters parameters = CaptureProxy.parseArgs(
            new String[] {
                "--destinationUri",
                "invalid:9200",
                "--listenPort",
                "80",
                "--kafkaConnection",
                kafkaBrokerString,
                "--enableMSKAuth" }
        );
        Properties props = CaptureProxy.buildKafkaProperties(parameters);

        Assertions.assertEquals(CaptureProxy.DEFAULT_KAFKA_CLIENT_ID, props.get(ProducerConfig.CLIENT_ID_CONFIG));
        Assertions.assertEquals(kafkaBrokerString, props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.StringSerializer",
            props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        );
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArraySerializer",
            props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        );
        Assertions.assertEquals("SASL_SSL", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        Assertions.assertEquals("AWS_MSK_IAM", props.get(SaslConfigs.SASL_MECHANISM));
        Assertions.assertEquals(
            "software.amazon.msk.auth.iam.IAMLoginModule required;",
            props.get(SaslConfigs.SASL_JAAS_CONFIG)
        );
        Assertions.assertEquals(
            "software.amazon.msk.auth.iam.IAMClientCallbackHandler",
            props.get(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS)
        );
    }

    @Test
    public void testBuildKafkaPropertiesWithPropertyFile() throws IOException {
        CaptureProxy.Parameters parameters = CaptureProxy.parseArgs(
            new String[] {
                "--destinationUri",
                "invalid:9200",
                "--listenPort",
                "80",
                "--kafkaConnection",
                kafkaBrokerString,
                "--enableMSKAuth",
                "--kafkaConfigFile",
                "src/test/resources/simple-kafka.properties" }
        );
        Properties props = CaptureProxy.buildKafkaProperties(parameters);

        // Default settings which property file does not provide remain intact
        Assertions.assertEquals(CaptureProxy.DEFAULT_KAFKA_CLIENT_ID, props.get(ProducerConfig.CLIENT_ID_CONFIG));
        Assertions.assertEquals(kafkaBrokerString, props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArraySerializer",
            props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        );

        // Default settings which property file provides are overriden
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArraySerializer",
            props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        );

        // Additional settings from property file are added
        Assertions.assertEquals("212", props.get("reconnect.backoff.max.ms"));

        // Settings needed for other passed arguments (i.e. --enableMSKAuth) are ignored by property file
        Assertions.assertEquals("SASL_SSL", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
    }
}
