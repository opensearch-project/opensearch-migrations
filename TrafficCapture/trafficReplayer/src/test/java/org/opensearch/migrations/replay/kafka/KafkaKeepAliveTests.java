package org.opensearch.migrations.replay.kafka;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("requiresDocker")
public class KafkaKeepAliveTests {
    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final String HEARTBEAT_INTERVAL_MS_KEY = "heartbeat.interval.ms";
    public static final long MAX_POLL_INTERVAL_MS = 1000;
    public static final long HEARTBEAT_INTERVAL_MS = 300;
    public static final String testTopicName = "TEST_TOPIC";

    Producer<String, byte[]> kafkaProducer;
    AtomicInteger sendCompleteCount;
    Properties kafkaProperties;
    BlockingTrafficSource trafficSource;
    ArrayList<ITrafficStreamKey> keysReceived;

    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private KafkaContainer embeddedKafkaBroker =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @BeforeEach
    private void setupTestCase() throws Exception {
        kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        this.sendCompleteCount = new AtomicInteger(0);
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 0, sendCompleteCount).get();
        Assertions.assertEquals(1, sendCompleteCount.get());

        this.kafkaProperties = KafkaProtobufConsumer.buildKafkaProperties(embeddedKafkaBroker.getBootstrapServers(),
                TEST_GROUP_CONSUMER_ID, false,  null);
        Assertions.assertNull(kafkaProperties.get(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY));

        kafkaProperties.put(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY, MAX_POLL_INTERVAL_MS+"");
        kafkaProperties.put(HEARTBEAT_INTERVAL_MS_KEY, HEARTBEAT_INTERVAL_MS+"");
        var kafkaConsumer = new KafkaConsumer<String,byte[]>(kafkaProperties);
        this.trafficSource = new BlockingTrafficSource(
                new KafkaProtobufConsumer(kafkaConsumer, testTopicName, Duration.ofMillis(MAX_POLL_INTERVAL_MS)),
                Duration.ZERO);
        this.keysReceived = new ArrayList<ITrafficStreamKey>();

        readNextNStreams(trafficSource,  keysReceived, 0, 1);
        trafficSource.stopReadsPast(Instant.EPOCH.plus(Duration.ofDays(1)));
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 1, sendCompleteCount);
    } 
    

    @Test
    @Tag("longTest")
    public void testTimeoutsDontOccurForSlowPolls() throws Exception {
        var pollIntervalMs = Optional.ofNullable(kafkaProperties.get(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY))
                .map(s->Integer.valueOf((String)s)).orElseThrow();
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(()-> {
                    try {
                        var k = keysReceived.get(0);
                        log.warn("Calling commit traffic stream for "+k);
                        trafficSource.commitTrafficStream(k);
                        log.info("finished committing traffic stream");
                        trafficSource.stopReadsPast(Instant.MAX);
                        log.warn("Stop reads past infinity");
                        // this is a way to signal back to the main thread that this thread is done
                        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 2, sendCompleteCount);
                    } catch (Exception e) {
                        Lombok.sneakyThrow(e);
                    }
                },
                pollIntervalMs, TimeUnit.MILLISECONDS);

        // wait for 2 messages so that they include the last one produced by the async schedule call previously
        readNextNStreams(trafficSource,  keysReceived, 1, 2);
        Assertions.assertEquals(3, keysReceived.size());
        // At this point, we've read all (3) messages produced , committed the first one
        // (all the way through to Kafka), and no commits are in-flight yet for the last two messages.
    }


    @Test
    @Tag("longTest")
    public void testBlockedReadsAndBrokenCommitsDontCauseReordering() throws Exception {
        var pollIntervalMs = Optional.ofNullable(kafkaProperties.get(KafkaProtobufConsumer.MAX_POLL_INTERVAL_KEY))
                .map(s->Integer.valueOf((String)s)).orElseThrow();
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(()-> {
                    try {
                        var k = keysReceived.get(0);
                        log.warn("Calling commit traffic stream for "+k);
                        trafficSource.commitTrafficStream(k);
                        log.info("finished committing traffic stream");
                        trafficSource.stopReadsPast(Instant.MAX);
                        log.warn("Stop reads past infinity");
                        // this is a way to signal back to the main thread that this thread is done
                        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 2, sendCompleteCount);
                    } catch (Exception e) {
                        Lombok.sneakyThrow(e);
                    }
                },
                pollIntervalMs, TimeUnit.MILLISECONDS);

        // wait for 2 messages so that they include the last one produced by the async schedule call previously
        readNextNStreams(trafficSource, keysReceived, 1, 2);
        Assertions.assertEquals(3, keysReceived.size());
        // At this point, we've read all (3) messages produced , committed the first one
        // (all the way through to Kafka), and no commits are in-flight yet for the last two messages.

        // the first message was committed even though we waited to pull the next message only because of touches
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 3, sendCompleteCount);
        readNextNStreams(trafficSource, keysReceived,2, 1 );
        //

        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 4, sendCompleteCount);

        // wait long enough to call the BlockingTrafficSource so that the kafka consumer will fall out
        // and make sure that we have only committed the one message within the delayed scheduler above
        // (we should have definitely hit that since readNextNStreams() will block
        Thread.sleep(2* MAX_POLL_INTERVAL_MS);
        readNextNStreams(trafficSource, keysReceived, 3, 1);
        Assertions.assertEquals(4, keysReceived.size());





        // the first message was committed even though we waited to pull the next message only because of touches
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 3, sendCompleteCount);
        readNextNStreams(trafficSource, keysReceived,2, 1 );
        //

        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 4, sendCompleteCount);

        // wait long enough to call the BlockingTrafficSource so that the kafka consumer will fall out
        // and make sure that we have only committed the one message within the delayed scheduler above
        // (we should have definitely hit that since readNextNStreams() will block
        Thread.sleep(2* MAX_POLL_INTERVAL_MS);
        readNextNStreams(trafficSource, keysReceived, 3, 1);
        Assertions.assertEquals(4, keysReceived.size());

    }
    
    @SneakyThrows
    private static void readNextNStreams(BlockingTrafficSource kafkaSource, List<ITrafficStreamKey> keysReceived,
                                         int from, int count) {
        Assertions.assertEquals(from, keysReceived.size());
        for (int i=0; i<count; ) {
            var trafficStreams = kafkaSource.readNextTrafficStreamChunk().get();
            trafficStreams.forEach(ts->{
                var tsk = ts.getKey();
                log.atInfo().setMessage(()->"checking for "+tsk).log();
                Assertions.assertFalse(keysReceived.contains(tsk));
                keysReceived.add(tsk);
            });
            log.info("Read "+trafficStreams.size()+" traffic streams");
            i += trafficStreams.size();
        }
    }
}
