package org.opensearch.migrations.replay;

import com.google.common.collect.Streams;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.migrations.replay.kafka.KafkaProtobufConsumer;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("requiresDocker")
public class KafkaRestartingTrafficReplayerTest {
    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC";
    public static final TrafficStream SENTINEL_TRAFFIC_STREAM =
            TrafficStream.newBuilder().setConnectionId(SentinelSensingTrafficSource.SENTINEL_CONNECTION_ID).build();

    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;
    public static final Duration MAX_WAIT_TIME_FOR_TOPIC = Duration.ofMillis(PRODUCER_SLEEP_INTERVAL_MS*2);

    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private KafkaContainer embeddedKafkaBroker =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    private static class CounterLimitedReceiverFactory implements Supplier<Consumer<SourceTargetCaptureTuple>> {
        AtomicInteger nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        @Override
        public Consumer<SourceTargetCaptureTuple> get() {
            log.info("StopAt="+nextStopPointRef.get());
            var stopPoint = nextStopPointRef.get();
            var counter = new AtomicInteger();
            return tuple -> {
                if (counter.incrementAndGet() > stopPoint) {
                    log.error("Request received after our ingest threshold. Throwing.  Discarding " +
                            tuple.uniqueRequestKey);
                    var nextStopPoint = stopPoint + new Random(stopPoint).nextInt(stopPoint + 1);
                    nextStopPointRef.compareAndSet(stopPoint, nextStopPoint);
                    throw new TrafficReplayerRunner.FabricatedErrorToKillTheReplayer(false);
                }
            };
        }
    }

    //@ParameterizedTest
    @CsvSource(value = {
            "3,false",
            "-1,false",
            "3,true",
            "-1,true",
    })
    @Tag("longTest")
    public void fullTest(int testSize, boolean randomize) throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response->TestHttpServerContext.makeResponse(random, response));
        var streamAndConsumer = TrafficStreamGenerator.generateStreamAndSumOfItsTransactions(testSize, randomize);
        var trafficStreams = streamAndConsumer.stream.collect(Collectors.toList());
        log.atInfo().setMessage(()->trafficStreams.stream().map(ts-> TrafficStreamUtils.summarizeTrafficStream(ts))
                .collect(Collectors.joining("\n"))).log();

        loadStreamsToKafka(buildKafkaConsumer(),
                Streams.concat(trafficStreams.stream(), Stream.of(SENTINEL_TRAFFIC_STREAM)));
        TrafficReplayerRunner.runReplayerUntilSourceWasExhausted(streamAndConsumer.numHttpTransactions,
                httpServer.localhostEndpoint(), new CounterLimitedReceiverFactory(),
                () -> new SentinelSensingTrafficSource(
                        new KafkaProtobufConsumer(buildKafkaConsumer(), TEST_TOPIC_NAME, null)));
        log.error("done");
    }

    @SneakyThrows
    private KafkaConsumer<String, byte[]> buildKafkaConsumer() {
        var kafkaConsumerProps = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false, null);
        kafkaConsumerProps.setProperty("max.poll.interval.ms", "5000");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        log.atInfo().setMessage(()->"Just built KafkaConsumer="+kafkaConsumer).log();
        return kafkaConsumer;
    }

    private void loadStreamsToKafka(KafkaConsumer<String, byte[]> kafkaConsumer,
                                    Stream<TrafficStream> streams) throws Exception {
        var kafkaProducer = buildKafkaProducer();
        var counter = new AtomicInteger();
        loadStreamsAsynchronouslyWithCloseableResource(kafkaConsumer, streams, s -> s.forEach(trafficStream ->
                writeTrafficStreamRecord(kafkaProducer, new TrafficStreamWithEmbeddedKey(trafficStream),
                        "KEY_" + counter.incrementAndGet())));
    }

    private <R extends AutoCloseable> void
    loadStreamsAsynchronouslyWithCloseableResource(KafkaConsumer<String, byte[]> kafkaConsumer, R closeableResource,
                                                   Consumer<R> loader)
            throws Exception {
        try {
            new Thread(()->loader.accept(closeableResource)).start();
            var startTime = Instant.now();
            while (!kafkaConsumer.listTopics().isEmpty()) {
                Thread.sleep(10);
                Assertions.assertTrue(Duration.between(startTime, Instant.now()).compareTo(MAX_WAIT_TIME_FOR_TOPIC) < 0);
            }
        } finally {
            closeableResource.close();
        }
    }

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

    private Supplier<ISimpleTrafficCaptureSource>
    loadStreamsToKafkaFromCompressedFile(KafkaConsumer<String, byte[]> kafkaConsumer,
                                         String filename, int recordCount) throws Exception {
        var kafkaProducer = buildKafkaProducer();
        loadStreamsAsynchronouslyWithCloseableResource(kafkaConsumer, new V0_1TrafficCaptureSource(filename),
                originalTrafficSource -> {
                    try {
                        for (int i = 0; i < recordCount; ++i) {
                            List<ITrafficStreamWithKey> chunks = null;
                            chunks = originalTrafficSource.readNextTrafficStreamChunk().get();
                            for (int j = 0; j < chunks.size(); ++j) {
                                writeTrafficStreamRecord(kafkaProducer, chunks.get(j), "KEY_" + i + "_" + j);
                            }
                        }
                    } catch (Exception e) {
                        throw Lombok.sneakyThrow(e);
                    }
                });
        return () -> new KafkaProtobufConsumer(kafkaConsumer, TEST_TOPIC_NAME, null);
    }

    @SneakyThrows
    private static void writeTrafficStreamRecord(Producer<String, byte[]> kafkaProducer,
                                                 ITrafficStreamWithKey trafficStreamAndKey,
                                                 String recordId) {
        while (true) {
            try {
                var record = new ProducerRecord(TEST_TOPIC_NAME, recordId, trafficStreamAndKey.getStream().toByteArray());
                log.info("sending record with trafficStream=" + trafficStreamAndKey.getKey());
                var sendFuture = kafkaProducer.send(record, (metadata, exception) -> {
                    log.atInfo().setCause(exception).setMessage(() -> "completed send of TrafficStream with key=" +
                            trafficStreamAndKey.getKey() + " metadata=" + metadata).log();
                });
                var recordMetadata = sendFuture.get();
                log.info("finished publishing record... metadata=" + recordMetadata);
                break;
            } catch (Exception e) {
                log.error("Caught exception while trying to publish a record to Kafka.  Blindly retrying.");
                continue;
            }
        }
        Thread.sleep(PRODUCER_SLEEP_INTERVAL_MS);
    }
}
