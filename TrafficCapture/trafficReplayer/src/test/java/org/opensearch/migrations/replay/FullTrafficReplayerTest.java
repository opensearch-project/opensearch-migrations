package org.opensearch.migrations.replay;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.kafka.KafkaProtobufConsumer;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Testcontainers
//@WrapWithNettyLeakDetection(repetitions = 1)
public class FullTrafficReplayerTest {

    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final int TEST_RECORD_COUNT = 100;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "TEST_TRAFFIC_STREAM_ID_STRING";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS*2);


    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private KafkaContainer embeddedKafkaBroker =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));;

    Producer<String, byte[]> buildKafkaProducer() {
        var kafkaProps = new Properties();
        kafkaProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        kafkaProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        // Property details: https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#delivery-timeout-ms
        kafkaProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        kafkaProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.CLIENT_ID_CONFIG, TEST_GROUP_PRODUCER_ID);
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafkaBroker.getBootstrapServers());
        try {
            return new KafkaProducer(kafkaProps);
        } catch (Exception e) {
            log.atError().setCause(e).log();
            System.exit(1);
            throw e;
        }
    }

    //@Test
    @Tag("longTest")
    public void fullTest() throws Exception {
        var kafkaConsumerProps = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false,  null);
        kafkaConsumerProps.setProperty("max.poll.interval.ms", "10000");
        var kafkaConsumer = new KafkaConsumer<String,byte[]>(kafkaConsumerProps);

        var previouslyCompletelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();
        var httpServer = SimpleNettyHttpServer.makeServer(false, TestHttpServerContext::makeResponse);
        Consumer<SourceTargetCaptureTuple> tupleReceiver = t -> {
            var key = t.sourcePair.requestKey;
            var keyString = key.trafficStreamKey + "_" + key.getSourceRequestIndex();
            previouslyCompletelyHandledItems.put(keyString, t);
        };

        try (var originalTrafficSource = new V0_1TrafficCaptureSource("migrationLogs/kafkaOutput_09_23.proto.gz")) {
            new Thread(()->loadKafkaData(originalTrafficSource, TEST_RECORD_COUNT)).start();
            var startTime = Instant.now();
            while (!kafkaConsumer.listTopics().isEmpty()) {
                Thread.sleep(10);
                Assertions.assertTrue(Duration.between(startTime, Instant.now()).compareTo(MAX_WAIT_TIME_FOR_TOPIC) < 0);
            }
        }
        runTrafficReplayer(kafkaConsumer, httpServer, tupleReceiver);
        //Assertions.assertEquals();
        log.error("done");
    }

    private static void runTrafficReplayer(KafkaConsumer<String, byte[]> kafkaConsumer,
                                           SimpleNettyHttpServer httpServer,
                                           Consumer<SourceTargetCaptureTuple> tupleReceiver) throws Exception {
        var tr = new TrafficReplayer(httpServer.localhostEndpoint(),
                new StaticAuthTransformerFactory("TEST"),
                true, 10, 10*1024,
                TrafficReplayer.buildDefaultJsonTransformer(httpServer.localhostEndpoint().getHost()));

        try (var os = new NullOutputStream();
             var bos = new BufferedOutputStream(os);
             var trafficSource = new KafkaProtobufConsumer(kafkaConsumer, TEST_TOPIC_NAME, null);
             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.runReplayWithIOStreams(Duration.ofSeconds(70), blockingTrafficSource, bos,
                    new TimeShifter(10 * 1000), tupleReceiver);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage(() -> "eating exception to check for memory leaks.").log();
            throw e;
        }
    }

    @SneakyThrows
    private void loadKafkaData(ISimpleTrafficCaptureSource originalTrafficSource, int recordCount) {
        var kafkaProducer = buildKafkaProducer();

        for (int i = 0; i < recordCount; ++i) {
            var chunks = originalTrafficSource.readNextTrafficStreamChunk().get();
            for (int j = 0; j < chunks.size(); ++j) {
                var record = new ProducerRecord(TEST_TOPIC_NAME, "KEY_" + i + "_" + j,
                        chunks.get(j).getStream().toByteArray());
                var sendFuture = kafkaProducer.send(record, (metadata, exception) -> {});
                sendFuture.get();
                Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
            }
        }
    }
}
