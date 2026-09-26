package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.kafkasource.KafkaConsumerSourcePort;
import org.opensearch.migrations.replay.kafkasource.KafkaSourcePort;
import org.opensearch.migrations.testutils.SharedDockerImageNames;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

// REBUILD-LIMBO(G10) -- inherited bodies remain marked and recoverable; the live G9
// replacement follows the marked regions. Javadoc stays outside the regions.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest ReplayReadGate . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class KafkaCommitsWorkBetweenLongPollsTest extends InstrumentationTest {
    private static final long DEFAULT_POLL_INTERVAL_MS = 500;
    private static final int NUM_RUNS = 5;
    public static final String TEST_TOPIC_NAME = "test-topic";
    @Container
    // see
    // https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final ConfluentKafkaContainer embeddedKafkaBroker = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @SneakyThrows
    private KafkaConsumer<String, byte[]> buildKafkaConsumer() {
        var kafkaConsumerProps = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            "TEST_GROUP_CONSUMER_ID",
            "none",
            null,
            null,
            null
        );
        kafkaConsumerProps.setProperty("max.poll.interval.ms", DEFAULT_POLL_INTERVAL_MS + "");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        log.atInfo().setMessage("Just built KafkaConsumer={}").addArgument(kafkaConsumer).log();
        return kafkaConsumer;
    }

    @Test
    public void testThatCommitsAndReadsKeepWorking() throws Exception {
        var kafkaSource = new KafkaTrafficCaptureSource(
            rootContext,
            buildKafkaConsumer(),
            TEST_TOPIC_NAME,
            Duration.ofMillis(DEFAULT_POLL_INTERVAL_MS / 3)
        );
        var blockingSource = new BlockingTrafficSource(kafkaSource, Duration.ofMinutes(5));
        var readGate = new ReplayReadGate(Duration.ofMinutes(5), blockingSource);
        var kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        var itemQueue = new LinkedBlockingQueue<
            List<org.opensearch.migrations.replay.traffic.source.SourceInput>>();
        readGate.advanceTo(Instant.EPOCH.plus(Duration.ofMillis(1)));

        new Thread(() -> {
            try {
                for (int i = 0; i < NUM_RUNS; ++i) {
                    sendNextMessage(kafkaProducer, i);
                    if (i > 0) {
                        readGate.advanceTo(getTimeAtPoint(i - 1).plus(Duration.ofMillis(1)));
                    }
                    log.info("PUTMSG\n\n");
                    var chunks = itemQueue.take();
                    Assertions.assertEquals(1, chunks.size());
                    var ts = (ITrafficStreamWithKey) chunks.get(0);
                    Thread.sleep(DEFAULT_POLL_INTERVAL_MS * 2);
                    log.info("committing " + ts.getKey());
                    blockingSource.recordProcessingFinished(
                        (org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId)
                            blockingSource.recordIdFor(ts.getKey())
                    ).toCompletableFuture().get();
                    readGate.advanceTo(getTimeAtPoint(i));
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

*/
// REBUILD-LIMBO-END(G10)

@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class KafkaCommitsWorkBetweenLongPollsTest {

    @Container
    private final ConfluentKafkaContainer kafka =
        new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Test
    void acceptedCommitCompletesDuringTheNextPausedLongPoll() throws Exception {
        var topic = "g9-commit-long-poll-" + System.nanoTime();
        KafkaTrafficCaptureSourceLongTermTest.createLogAppendTimeTopic(
            kafka.getBootstrapServers(),
            topic
        );
        try (var producer = KafkaTestUtils.buildKafkaProducer(
            kafka.getBootstrapServers()
        )) {
            producer.send(new ProducerRecord<>(
                topic,
                "key",
                KafkaTrafficCaptureSourceLongTermTest.capture(0).toByteArray()
            )).get();
        }

        var partition = new TopicPartition(topic, 0);
        var consumer = new KafkaConsumer<String, byte[]>(
            KafkaTrafficCaptureSourceLongTermTest.consumerProperties(
                kafka.getBootstrapServers(),
                "g9-commit-long-poll"
            )
        );
        var port = new KafkaConsumerSourcePort(consumer, Duration.ofSeconds(30));
        try {
            consumer.subscribe(List.of(topic));
            var records = Map.<TopicPartition, List<
                org.opensearch.migrations.replay.kafkasource.PolledKafkaRecord
            >>of();
            for (var attempt = 0;
                attempt < 5 && records.getOrDefault(partition, List.of()).isEmpty();
                attempt++) {
                records = port.poll();
            }
            Assertions.assertEquals(
                1,
                records.getOrDefault(partition, List.of()).size()
            );

            var resolution =
                new AtomicReference<KafkaSourcePort.CommitOutcome>();
            var submission = port.commitAsync(
                Map.of(partition, 1L),
                resolution::set
            );
            Assertions.assertTrue(submission.accepted());
            consumer.pause(Set.of(partition));
            port.poll();

            Assertions.assertEquals(
                KafkaSourcePort.CommitOutcome.ACKNOWLEDGED,
                resolution.get()
            );
            Assertions.assertEquals(
                1L,
                consumer.committed(Set.of(partition)).get(partition).offset()
            );
        } finally {
            port.close();
        }
    }
}
