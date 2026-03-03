package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class KafkaKeepAliveTests extends InstrumentationTest {
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
    // see
    // https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final ConfluentKafkaContainer embeddedKafkaBroker = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    private KafkaTrafficCaptureSource kafkaSource;

    /**
     * Set up the test case where we've produced and received 1 message, but have not yet committed it.
     * Another message is in the process of being produced.
     * The BlockingTrafficSource is blocked on everything after a point before the beginning of the test.
     * @throws Exception
     */
    @BeforeEach
    private void setupTestCase() throws Exception {
        kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        this.sendCompleteCount = new AtomicInteger(0);
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 0, sendCompleteCount).get();
        Assertions.assertEquals(1, sendCompleteCount.get());

        this.kafkaProperties = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            TEST_GROUP_CONSUMER_ID,
            false,
            null
        );
        Assertions.assertNull(kafkaProperties.get(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY));

        kafkaProperties.put(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY, MAX_POLL_INTERVAL_MS + "");
        kafkaProperties.put(HEARTBEAT_INTERVAL_MS_KEY, HEARTBEAT_INTERVAL_MS + "");
        kafkaProperties.put("max.poll.records", 1);
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaProperties);
        this.kafkaSource = new KafkaTrafficCaptureSource(
            rootContext,
            kafkaConsumer,
            testTopicName,
            Duration.ofMillis(MAX_POLL_INTERVAL_MS)
        );
        this.trafficSource = new BlockingTrafficSource(kafkaSource, Duration.ZERO);
        this.keysReceived = new ArrayList<>();

        readNextNStreams(rootContext, trafficSource, keysReceived, 0, 1);
        KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 1, sendCompleteCount);
    }

    @Test
    public void testTimeoutsDontOccurForSlowPolls() throws Exception {
        var pollIntervalMs = Optional.ofNullable(kafkaProperties.get(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY))
            .map(s -> Integer.valueOf((String) s))
            .orElseThrow();
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            try {
                var k = keysReceived.get(0);
                log.info("Calling commit traffic stream for " + k);
                trafficSource.commitTrafficStream(k);
                log.info("finished committing traffic stream");
                log.info("Stop reads to infinity");
                // this is a way to signal back to the main thread that this thread is done
                KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 2, sendCompleteCount);
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }, pollIntervalMs, TimeUnit.MILLISECONDS);

        // wait for 2 messages so that they include the last one produced by the async schedule call previously
        readNextNStreams(rootContext, trafficSource, keysReceived, 1, 2);
        Assertions.assertEquals(3, keysReceived.size());
        // At this point, we've read all (3) messages produced , committed the first one
        // (all the way through to Kafka), and no commits are in-flight yet for the last two messages.
    }

    @Test
    public void testBlockedReadsAndBrokenCommitsDontCauseReordering() throws Exception {
        for (int i = 0; i < 2; ++i) {
            KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, 1 + i, sendCompleteCount).get();
        }
        readNextNStreams(rootContext, trafficSource, keysReceived, 1, 1);

        trafficSource.commitTrafficStream(keysReceived.get(0));
        log.info(
            "Called commitTrafficStream but waiting long enough for the client to leave the group.  "
                + "That will make the previous commit a 'zombie-commit' that should easily be dropped."
        );

        log.info(
            "1 message was committed, but not synced, 1 message is being processed."
                + "wait long enough to fall out of the group before we can commit"
        );
        Thread.sleep(2 * MAX_POLL_INTERVAL_MS);

        var keysReceivedUntilDrop1 = keysReceived;
        keysReceived = new ArrayList<>();

        log.info("re-establish a client connection so that the following commit will work");
        log.atInfo().setMessage("1 ...{}").addArgument(this::renderNextCommitsAsString).log();
        readNextNStreams(rootContext, trafficSource, keysReceived, 0, 1);
        log.atInfo().setMessage("2 ...{}").addArgument(this::renderNextCommitsAsString).log();

        log.info("wait long enough to fall out of the group again");
        Thread.sleep(2 * MAX_POLL_INTERVAL_MS);

        var keysReceivedUntilDrop2 = keysReceived;
        keysReceived = new ArrayList<>();
        log.atInfo().setMessage("re-establish... 3 ...{}").addArgument(this::renderNextCommitsAsString).log();
        readNextNStreams(rootContext, trafficSource, keysReceived, 0, 1);
        trafficSource.commitTrafficStream(keysReceivedUntilDrop1.get(1));
        log.atInfo().setMessage("re-establish... 4 ...{}").addArgument(this::renderNextCommitsAsString).log();
        readNextNStreams(rootContext, trafficSource, keysReceived, 1, 1);
        log.atInfo().setMessage("5 ...{}").addArgument(this::renderNextCommitsAsString).log();

        Thread.sleep(2 * MAX_POLL_INTERVAL_MS);
        var keysReceivedUntilDrop3 = keysReceived;
        keysReceived = new ArrayList<>();
        readNextNStreams(rootContext, trafficSource, keysReceived, 0, 3);
        log.atInfo().setMessage("6 ...{}").addArgument(kafkaSource.trackingKafkaConsumer::nextCommitsToString).log();
        trafficSource.close();
    }

    private String renderNextCommitsAsString() {
        return kafkaSource.trackingKafkaConsumer.nextCommitsToString();
    }

    @SneakyThrows
    private void readNextNStreams(
        TestContext rootContext,
        BlockingTrafficSource trafficSource,
        List<ITrafficStreamKey> keysReceived,
        int from,
        int count
    ) {
        Assertions.assertEquals(from, keysReceived.size());
        for (int i = 0; i < count;) {
            var trafficStreams = trafficSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            for (var ts : trafficStreams) {
                if (ts instanceof TrafficSourceReaderInterruptedClose) {
                    // Drain synthetic closes and decrement the counter so real records can resume
                    var key = ts.getKey();
                    log.atInfo().setMessage("Draining synthetic close for {}").addArgument(key).log();
                    kafkaSource.onNetworkConnectionClosed(key.getConnectionId(), 0, key.getSourceGeneration());
                    continue;
                }
                var tsk = ts.getKey();
                log.atInfo().setMessage("checking for {}").addArgument(tsk).log();
                Assertions.assertFalse(keysReceived.contains(tsk));
                keysReceived.add(tsk);
                i++;
            }
            log.info("Read " + trafficStreams.size() + " traffic streams");
        }
    }
}
