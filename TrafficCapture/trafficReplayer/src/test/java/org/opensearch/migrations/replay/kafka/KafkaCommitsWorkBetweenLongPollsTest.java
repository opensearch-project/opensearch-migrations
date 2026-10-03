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
