package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class KafkaTrafficCaptureSourceLongTermTest extends InstrumentationTest {

    public static final int TEST_RECORD_COUNT = 10;
    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;

    @Container
    // see
    // https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final ConfluentKafkaContainer embeddedKafkaBroker = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Test
    @Tag("isolatedTest")
    public void testTrafficCaptureSource() throws Exception {
        String testTopicName = "TEST_TOPIC";

        var kafkaConsumerProps = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            TEST_GROUP_CONSUMER_ID,
            false,
            null
        );
        final long MAX_POLL_MS = 10000;
        kafkaConsumerProps.setProperty(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY, MAX_POLL_MS + "");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        var kafkaTrafficCaptureSource = new KafkaTrafficCaptureSource(
            rootContext,
            kafkaConsumer,
            testTopicName,
            Duration.ofMillis(MAX_POLL_MS)
        );

        var kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        var sendCompleteCount = new AtomicInteger(0);
        var scheduledIterationsCount = new AtomicInteger(0);
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            var i = scheduledIterationsCount.getAndIncrement();
            if (i >= TEST_RECORD_COUNT) {
                executor.shutdown();
            } else {
                KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, i, sendCompleteCount);
            }
        }, 0, PRODUCER_SLEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);

        for (int i = 0; i < TEST_RECORD_COUNT;) {
            Thread.sleep(getSleepAmountMsForProducerRun(i));
            var nextChunkFuture = kafkaTrafficCaptureSource.readNextTrafficStreamChunk(
                rootContext::createReadChunkContext
            );
            var recordsList = nextChunkFuture.get(
                (2 * TEST_RECORD_COUNT) * PRODUCER_SLEEP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            for (int j = 0; j < recordsList.size(); ++j) {
                Assertions.assertEquals(
                    KafkaTestUtils.getConnectionId(i + j),
                    recordsList.get(j).getStream().getConnectionId()
                );
            }
            log.info("Got " + recordsList.size() + " records and already had " + i);
            i += recordsList.size();
        }

        Assertions.assertEquals(TEST_RECORD_COUNT, sendCompleteCount.get());
        Assertions.assertThrows(TimeoutException.class, () -> {
            var rogueChunk = kafkaTrafficCaptureSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(1, TimeUnit.SECONDS);
            if (rogueChunk.isEmpty()) {
                // TimeoutExceptions cannot be thrown by the supplier of the CompletableFuture today, BUT we
                // could long-poll on the broker for longer than the timeout value supplied in the get() call above
                throw new TimeoutException(
                    "read actually returned 0 items, but transforming this to a "
                        + "TimeoutException because either result would be valid."
                );
            }
            log.error("rogue chunk: " + rogueChunk);
        });
    }

    private long getSleepAmountMsForProducerRun(int i) {
        return 1 * 1000;
    }
}
