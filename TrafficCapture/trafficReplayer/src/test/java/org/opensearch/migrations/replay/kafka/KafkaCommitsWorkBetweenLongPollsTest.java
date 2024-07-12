package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("requiresDocker")
public class KafkaCommitsWorkBetweenLongPollsTest extends InstrumentationTest {
    private static final long DEFAULT_POLL_INTERVAL_MS = 1000;
    private static final int NUM_RUNS = 5;
    public static final String TEST_TOPIC_NAME = "test-topic";
    @Container
    // see
    // https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final KafkaContainer embeddedKafkaBroker = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    @SneakyThrows
    private KafkaConsumer<String, byte[]> buildKafkaConsumer() {
        var kafkaConsumerProps = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            "TEST_GROUP_CONSUMER_ID",
            false,
            null
        );
        kafkaConsumerProps.setProperty("max.poll.interval.ms", DEFAULT_POLL_INTERVAL_MS + "");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        log.atInfo().setMessage(() -> "Just built KafkaConsumer=" + kafkaConsumer).log();
        return kafkaConsumer;
    }

    @Test
    @Tag("longTest")
    public void testThatCommitsAndReadsKeepWorking() throws Exception {
        var kafkaSource = new KafkaTrafficCaptureSource(
            rootContext,
            buildKafkaConsumer(),
            TEST_TOPIC_NAME,
            Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS / 3)
        );
        var blockingSource = new BlockingTrafficSource(kafkaSource, Duration.ofMinutes(5));
        var kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        var itemQueue = new LinkedBlockingQueue<List<ITrafficStreamWithKey>>();
        blockingSource.stopReadsPast(Instant.EPOCH.plus(Duration.ofMillis(1)));

        new Thread(() -> {
            try {
                for (int i = 0; i < NUM_RUNS; ++i) {
                    sendNextMessage(kafkaProducer, i);
                    if (i > 0) {
                        blockingSource.stopReadsPast(getTimeAtPoint(i - 1).plus(Duration.ofMillis(1)));
                    }
                    log.info("PUTMSG\n\n");
                    var chunks = itemQueue.take();
                    Assertions.assertEquals(1, chunks.size());
                    var ts = chunks.get(0);
                    Thread.sleep(DEFAULT_POLL_INTERVAL_MS * 2);
                    log.info("committing " + ts.getKey());
                    blockingSource.commitTrafficStream(ts.getKey());
                    blockingSource.stopReadsPast(getTimeAtPoint(i));
                }
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).start();

        for (int i = 0; i < NUM_RUNS; ++i) {
            while (true) {
                var chunks = blockingSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
                if (!chunks.isEmpty()) {
                    Assertions.assertEquals(1, chunks.size());
                    log.info("GETMSG\n\n");
                    itemQueue.put(chunks);
                    break;
                }
            }
        }
        //
        // var spans = testSpanExporter.getFinishedSpanItems();
        // Assertions.assertFalse(spans.isEmpty(), "No spans were found");
        //
        // var metrics = testMetricExporter.getFinishedMetricItems();
        // Assertions.assertFalse(metrics.isEmpty(), "No metrics were found");

    }

    static Instant getTimeAtPoint(int i) {
        return Instant.EPOCH.plus(Duration.ofHours(1 + i));
    }

    private void sendNextMessage(Producer<String, byte[]> kafkaProducer, int i) {
        var ts = KafkaTestUtils.makeTestTrafficStreamWithFixedTime(getTimeAtPoint(i), i);
        KafkaTestUtils.writeTrafficStreamRecord(kafkaProducer, ts, TEST_TOPIC_NAME, "" + i);
    }
}
