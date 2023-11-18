package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("requiresDocker")
public class KafkaProtobufConsumerLongTermTest {

    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    public static final int TEST_RECORD_COUNT = 10;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "TEST_TRAFFIC_STREAM_ID_STRING";
    private static final String FAKE_READ_PACKET_DATA = "Fake pa";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;

    public static final String HEARTBEAT_INTERVAL_MS_KEY = "heartbeat.interval.ms";

    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private KafkaContainer embeddedKafkaBroker =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));


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

    TrafficStream makeTestTrafficStream(Instant t, int i) {
        var timestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        var tsb = TrafficStream.newBuilder()
                .setNumber(i);
        // TODO - add something for setNumberOfThisLastChunk.  There's no point in doing that now though
        //        because the code doesn't make any distinction between the very last one and the previous ones
        return tsb.setNodeId(TEST_NODE_ID)
                .setConnectionId(getConnectionId(i))
                .addSubStream(TrafficObservation.newBuilder().setTs(timestamp)
                        .setRead(ReadObservation.newBuilder()
                                .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                                .build())
                        .build()).build();

    }

    private String getConnectionId(int i) {
        return TEST_TRAFFIC_STREAM_ID_STRING + "_" + i;
    }

    @Test
    @Tag("longTest")
    public void testTimeoutsDontOccurForSlowPolls() throws Exception {
        String testTopicName = "TEST_TOPIC";

        log.info("Starting test");
        log.error("Starting test");

        var kafkaProducer = buildKafkaProducer();
        var sendCompleteCount = new AtomicInteger(0);
        produceKafkaRecord(testTopicName, kafkaProducer, 0, sendCompleteCount).get();
        Assertions.assertEquals(1, sendCompleteCount.get());

        var kafkaProperties = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false,  null);
        Assertions.assertNull(kafkaProperties.get(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY));

        final long MAX_POLL_INTERVAL_MS = 1000;
        final long HEARTBEAT_INTERVAL_MS = 300;

        kafkaProperties.put(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY, MAX_POLL_INTERVAL_MS+"");
        kafkaProperties.put(HEARTBEAT_INTERVAL_MS_KEY, HEARTBEAT_INTERVAL_MS+"");
        var kafkaConsumer = new KafkaConsumer<String,byte[]>(kafkaProperties);
        var trafficSource = new BlockingTrafficSource(
                new KafkaProtobufConsumer(kafkaConsumer, testTopicName, Duration.ofMillis(MAX_POLL_INTERVAL_MS)),
                Duration.ZERO);
        var keysReceived = new ArrayList<ITrafficStreamKey>();

        readNextNStreams(trafficSource,  keysReceived, 0, 1);
        trafficSource.stopReadsPast(Instant.EPOCH.plus(Duration.ofDays(1)));
        produceKafkaRecord(testTopicName, kafkaProducer, 1, sendCompleteCount);

        var pollIntervalMs = Optional.ofNullable(kafkaProperties.get(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY))
                .map(s->Integer.valueOf((String)s)).orElseThrow();
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(()-> {
                    try {
                        var k = keysReceived.get(0);
                        log.warn("Calling commit traffic stream for "+k);
                        trafficSource.commitTrafficStream(k);
                        trafficSource.stopReadsPast(Instant.MAX);
                        log.warn("Stop reads past infinity");
                        Thread.sleep(1000);
                        produceKafkaRecord(testTopicName, kafkaProducer, 2, sendCompleteCount);
                    } catch (Exception e) {
                        Lombok.sneakyThrow(e);
                    }
                },
                pollIntervalMs, TimeUnit.MILLISECONDS);

        log.info("finished committing traffic stream");
        readNextNStreams(trafficSource,  keysReceived, 1, 2);
        Assertions.assertEquals(3, keysReceived.size());
    }

    @SneakyThrows
    private static void readNextNStreams(BlockingTrafficSource kafkaSource, List<ITrafficStreamKey> keysReceived,
                                     int from, int count) {
        Assertions.assertEquals(from, keysReceived.size());
        for (int i=0; i<count; ) {
            var trafficStreams = kafkaSource.readNextTrafficStreamChunk().get();
            trafficStreams.forEach(ts->{
                var tsk = ts.getKey();
                Assertions.assertFalse(keysReceived.contains(tsk));
                keysReceived.add(tsk);
            });
            log.info("Read "+trafficStreams.size()+" traffic streams");
            i += trafficStreams.size();
        }

    }

    //@Test
    @Tag("longTest")
    public void testTrafficCaptureSource() throws Exception {
        String testTopicName = "TEST_TOPIC";

        var kafkaConsumerProps = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false,  null);
        final long MAX_POLL_MS = 10000;
        kafkaConsumerProps.setProperty(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY, MAX_POLL_MS+"");
        var kafkaConsumer = new KafkaConsumer<String,byte[]>(kafkaConsumerProps);
        var kafkaTrafficCaptureSource = new KafkaProtobufConsumer(kafkaConsumer, testTopicName,
                Duration.ofMillis(MAX_POLL_MS));

        var kafkaProducer = buildKafkaProducer();
        var sendCompleteCount = new AtomicInteger(0);
        var scheduledIterationsCount = new AtomicInteger(0);
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(()->{
            var i = scheduledIterationsCount.getAndIncrement();
            if (i >= TEST_RECORD_COUNT) {
                executor.shutdown();
            } else {
                produceKafkaRecord(testTopicName, kafkaProducer, i, sendCompleteCount);
            }
        }, 0, PRODUCER_SLEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);

        for (int i=0; i<TEST_RECORD_COUNT; ) {
            Thread.sleep(getSleepAmountMsForProducerRun(i));
            var nextChunkFuture = kafkaTrafficCaptureSource.readNextTrafficStreamChunk();
            var recordsList = nextChunkFuture.get((2+ TEST_RECORD_COUNT)*PRODUCER_SLEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
            for (int j=0; j<recordsList.size(); ++j) {
                Assertions.assertEquals(getConnectionId(i+j), recordsList.get(j).getStream().getConnectionId());
            }
            log.info("Got "+recordsList.size()+" records and already had " + i);
            i += recordsList.size();
        }
        Assertions.assertEquals(TEST_RECORD_COUNT, sendCompleteCount.get());
        Assertions.assertThrows(TimeoutException.class, ()-> {
                var rogueChunk = kafkaTrafficCaptureSource.readNextTrafficStreamChunk().get(1, TimeUnit.SECONDS);
                if (rogueChunk.isEmpty()) {
                    // TimeoutExceptions cannot be thrown by the supplier of the CompletableFuture today, BUT we
                    // could long-poll on the broker for longer than the timeout value supplied in the get() call above
                    throw new TimeoutException("read actually returned 0 items, but transforming this to a " +
                            "TimeoutException because either result would be valid.");
                }
                log.error("rogue chunk: "+ rogueChunk);
        });
    }

    private long getSleepAmountMsForProducerRun(int i) {
        return 1*1000;
    }

    private Future produceKafkaRecord(String testTopicName, Producer<String, byte[]> kafkaProducer, int i,
                                      AtomicInteger sendCompleteCount) {
        var trafficStream = makeTestTrafficStream(Instant.now(), i);
        var record = new ProducerRecord(testTopicName, makeKey(i), trafficStream.toByteArray());
        return kafkaProducer.send(record, (metadata, exception) -> {
            sendCompleteCount.incrementAndGet();
        });
    }

    @NotNull
    private static String makeKey(int i) {
        return "KEY_" + i;
    }
}