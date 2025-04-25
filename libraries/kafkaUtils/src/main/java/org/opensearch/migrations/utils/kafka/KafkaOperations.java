package org.opensearch.migrations.utils.kafka;

import org.opensearch.migrations.replay.traffic.kafka.KafkaStreamLoader;
import org.opensearch.migrations.tracing.TestContext;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;

import static org.opensearch.migrations.replay.traffic.kafka.KafkaTestUtils.buildKafkaConsumer;
import static org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy.buildKafkaProperties;

@Slf4j
public class KafkaOperations {
    public static final long DEFAULT_POLL_INTERVAL_MS = 5000;
    public static final String TOPIC_NAME = "logging-traffic-topic";


    public void loadStreamsToKafkaFromCompressedFile(
            String filename,
            String kafkaPropertiesFile,
            String kafkaConnection,
            String kafkaClientId,
            boolean mskAuthEnabled
    ) throws Exception {
        var kafkaProperties = buildKafkaProperties(kafkaPropertiesFile, kafkaConnection, kafkaClientId, mskAuthEnabled);
        var kafkaProducer = new KafkaProducer(kafkaProperties);
        var kafkaConsumer = buildKafkaConsumer(kafkaConnection, DEFAULT_POLL_INTERVAL_MS);
        var kafkaStreamLoader = new KafkaStreamLoader();
        kafkaStreamLoader.loadStreamsToKafkaFromCompressedFile(
                TestContext.noOtelTracking(),
                kafkaProducer,
                kafkaConsumer,
                TOPIC_NAME,
                filename,
                DEFAULT_POLL_INTERVAL_MS,
                10);
    }
}
