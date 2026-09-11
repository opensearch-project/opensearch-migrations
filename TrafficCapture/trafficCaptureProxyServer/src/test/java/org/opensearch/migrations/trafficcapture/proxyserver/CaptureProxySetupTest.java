package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;

import org.opensearch.migrations.trafficcapture.netty.CaptureFailurePolicy;
import org.opensearch.migrations.trafficcapture.netty.IncompleteRequestLimits;

import com.beust.jcommander.ParameterException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig.DEFAULT_KAFKA_CLIENT_ID;
import static org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig.buildKafkaProperties;

public class CaptureProxySetupTest {

    public static final String kafkaBrokerString = "invalid:9092";

    @Test
    void captureFailuresDefaultToFailClosedAndCanBeConfiguredFailOpen() {
        var defaults = CaptureProxy.parseArgs(new String[] {
            "--destinationUri", "invalid:9200",
            "--listenPort", "80",
            "--noCapture"
        });
        var failOpen = CaptureProxy.parseArgs(new String[] {
            "--destinationUri", "invalid:9200",
            "--listenPort", "80",
            "--noCapture",
            "--capture-failure-policy", "fail-open"
        });

        Assertions.assertEquals(CaptureFailurePolicy.FAIL_CLOSED, defaults.captureFailurePolicy);
        Assertions.assertEquals(CaptureFailurePolicy.FAIL_OPEN, failOpen.captureFailurePolicy);
        Assertions.assertThrows(
            ParameterException.class,
            () -> new CaptureProxy.CaptureFailurePolicyConverter().convert("sometimes")
        );
    }

    @Test
    void incompleteRequestLimitsHaveSafeDefaultsAndAreConfigurable() {
        var defaults = CaptureProxy.parseArgs(new String[] {
            "--destinationUri", "invalid:9200",
            "--listenPort", "80",
            "--noCapture"
        });
        var configured = CaptureProxy.parseArgs(new String[] {
            "--destinationUri", "invalid:9200",
            "--listenPort", "80",
            "--noCapture",
            "--max-request-assembly-duration-seconds", "17",
            "--max-incomplete-request-header-bytes", "4096",
            "--max-incomplete-request-total-bytes", "8192"
        });

        Assertions.assertEquals(
            IncompleteRequestLimits.DEFAULT_MAXIMUM_ASSEMBLY_DURATION,
            Duration.ofSeconds(defaults.maximumRequestAssemblyDurationSeconds)
        );
        Assertions.assertEquals(
            IncompleteRequestLimits.DEFAULT_MAXIMUM_HEADER_BYTES,
            defaults.maximumIncompleteRequestHeaderBytes
        );
        Assertions.assertEquals(
            IncompleteRequestLimits.DEFAULT_MAXIMUM_TOTAL_BYTES,
            defaults.maximumIncompleteRequestTotalBytes
        );
        Assertions.assertEquals(17, configured.maximumRequestAssemblyDurationSeconds);
        Assertions.assertEquals(4096, configured.maximumIncompleteRequestHeaderBytes);
        Assertions.assertEquals(8192, configured.maximumIncompleteRequestTotalBytes);
    }

    @Test
    void minimumActiveProxyCountDefaultsToOneAndIsConfigurable() {
        var defaults = CaptureProxy.parseArgs(new String[] {
            "--destinationUri", "invalid:9200",
            "--listenPort", "80",
            "--noCapture"
        });
        var configured = CaptureProxy.parseArgs(new String[] {
            "--destinationUri", "invalid:9200",
            "--listenPort", "80",
            "--noCapture",
            "--minimum-active-proxy-count", "3"
        });

        Assertions.assertEquals(1, defaults.minimumActiveProxyCount);
        Assertions.assertEquals(3, configured.minimumActiveProxyCount);
    }

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
        Properties props = buildKafkaProperties(parameters.kafkaParameters);

        Assertions.assertEquals(DEFAULT_KAFKA_CLIENT_ID, props.get(ProducerConfig.CLIENT_ID_CONFIG));
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
    public void testBuildKafkaPropertiesWithNormalizedKafkaFlagNames() throws IOException {
        CaptureProxy.Parameters parameters = CaptureProxy.parseArgs(
            new String[] {
                "--destinationUri",
                "invalid:9200",
                "--listenPort",
                "80",
                "--kafkaBrokers",
                kafkaBrokerString,
                "--kafkaPropertyFile",
                "src/test/resources/simple-kafka.properties",
                "--kafkaAuthType",
                "msk-iam" }
        );
        Properties props = buildKafkaProperties(parameters.kafkaParameters);

        Assertions.assertEquals(kafkaBrokerString, props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals("SASL_SSL", props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        Assertions.assertEquals("212", props.get("reconnect.backoff.max.ms"));
    }

    @Test
    public void testKafkaAuthFlagsRejectConflictingLegacyAndNormalizedValues() {
        var parameters = new org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig.KafkaParameters();
        parameters.legacyEnableMSKAuth = true;
        parameters.kafkaAuthType = "scram-sha-512";

        Assertions.assertThrows(IllegalArgumentException.class, parameters::validateKafkaAuthFlags);
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
        Properties props = buildKafkaProperties(parameters.kafkaParameters);

        Assertions.assertEquals(DEFAULT_KAFKA_CLIENT_ID, props.get(ProducerConfig.CLIENT_ID_CONFIG));
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
        Properties props = buildKafkaProperties(parameters.kafkaParameters);

        // Default settings which property file does not provide remain intact
        Assertions.assertEquals(DEFAULT_KAFKA_CLIENT_ID, props.get(ProducerConfig.CLIENT_ID_CONFIG));
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

    @Test
    public void testConvertStringToUriWithExplicitPort() {
        URI uri = CaptureProxy.convertStringToUri("https://search-my-domain.us-east-1.es.amazonaws.com:9200");
        Assertions.assertEquals(9200, uri.getPort());
        Assertions.assertEquals("https", uri.getScheme());
    }

    @Test
    public void testConvertStringToUriHttpsDefaultPort() {
        URI uri = CaptureProxy.convertStringToUri("https://search-my-domain.us-east-1.es.amazonaws.com");
        Assertions.assertEquals(443, uri.getPort());
        Assertions.assertEquals("https", uri.getScheme());
    }

    @Test
    public void testConvertStringToUriHttpDefaultPort() {
        URI uri = CaptureProxy.convertStringToUri("http://search-my-domain.us-east-1.es.amazonaws.com");
        Assertions.assertEquals(80, uri.getPort());
        Assertions.assertEquals("http", uri.getScheme());
    }

    @Test
    public void testConvertStringToUriExplicit443() {
        URI uri = CaptureProxy.convertStringToUri("https://search-my-domain.us-east-1.es.amazonaws.com:443");
        Assertions.assertEquals(443, uri.getPort());
    }
}
