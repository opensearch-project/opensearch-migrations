/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource.assignor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.replay.kafkasource.KafkaConsumerSourcePort;
import org.opensearch.migrations.testutils.SharedDockerImageNames;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * The assignor wired the way the replayer wires it — configured through consumer properties, with its
 * deadlines carried out by {@link KafkaConsumerSourcePort} polls — against a real broker's group protocol.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
class QuietWindowAssignorAgainstRealKafkaTest {
    private static final String TOPIC = "quiet-window";
    private static final int PARTITIONS = 6;
    private static final long QUIET_MILLIS = 4_000;

    @Container
    private final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    /** One group member on its own thread, polling through the source port like the Kafka owner does. */
    private final class Worker implements AutoCloseable {
        final String name;
        final AtomicReference<Set<Integer>> assignment = new AtomicReference<>(Set.of());
        final List<Set<Integer>> history = new CopyOnWriteArrayList<>();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final Thread thread;

        Worker(String name) {
            this.name = name;
            var control = new AssignorControl();
            var props = new Properties();
            props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "replayers");
            props.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, name);
            props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
            props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
            props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "250");
            props.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "6000");
            props.setProperty(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, QuietWindowAssignor.class.getName());
            props.put(QuietWindowAssignor.CONTROL_CONFIG, control);
            props.put(QuietWindowAssignor.QUIET_MILLIS_CONFIG, QUIET_MILLIS);
            thread = new Thread(() -> {
                try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
                    consumer.subscribe(List.of(TOPIC));
                    var port = new KafkaConsumerSourcePort(consumer, Duration.ofMillis(100), control);
                    while (!stop.get()) {
                        port.poll(Duration.ofMillis(100));
                        var current = new TreeSet<Integer>();
                        consumer.assignment().forEach(tp -> current.add(tp.partition()));
                        if (!current.equals(assignment.get())) {
                            assignment.set(Set.copyOf(current));
                            history.add(Set.copyOf(current));
                        }
                    }
                }
            }, "worker-" + name);
            thread.start();
        }

        @Override
        public void close() throws InterruptedException {
            stop.set(true);
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    @Test
    void startupWaitsScaleUpStandsByAndReplacementInheritsTheDepartedShare() throws Exception {
        try (var admin = Admin.create(Map.<String, Object>of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()
        ))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get();
        }

        try (var w1 = new Worker("w1"); var w2 = new Worker("w2")) {
            Thread.sleep(QUIET_MILLIS / 2);
            Assertions.assertTrue(w1.assignment.get().isEmpty() && w2.assignment.get().isEmpty(),
                "startup assigns nothing until membership has been quiet for Q");
            awaitTrue("both founders assigned", () -> w1.assignment.get().size() == 3 && w2.assignment.get().size() == 3);
            var w1Founded = w1.assignment.get();
            var w2Founded = w2.assignment.get();

            Set<Integer> w3Share;
            try (var w3 = new Worker("w3")) {
                Thread.sleep(QUIET_MILLIS / 2);
                Assertions.assertTrue(w3.assignment.get().isEmpty(), "a scale-up worker stands by until quiet");
                Assertions.assertEquals(w1Founded, w1.assignment.get(), "standby must not disturb owners");
                Assertions.assertEquals(w2Founded, w2.assignment.get(), "standby must not disturb owners");
                awaitTrue("w3 activated after the quiet window", () -> w3.assignment.get().size() == 2);
                awaitTrue("owners settle at 2 each", () -> w1.assignment.get().size() == 2 && w2.assignment.get().size() == 2);
                w3Share = w3.assignment.get();
            }

            var w1BeforeRestart = w1.assignment.get();
            var w2BeforeRestart = w2.assignment.get();
            try (var w4 = new Worker("w4")) {
                awaitTrue("replacement backfilled", () -> w4.assignment.get().equals(w3Share));
                Thread.sleep(QUIET_MILLIS + 2_000);
                Assertions.assertEquals(w3Share, w4.assignment.get(), "the replacement keeps exactly the departed share");
                Assertions.assertEquals(w1BeforeRestart, w1.assignment.get(), "a replacement must not move survivors");
                Assertions.assertEquals(w2BeforeRestart, w2.assignment.get(), "a replacement must not move survivors");
                Assertions.assertEquals(List.of(w3Share), w4.history, "the replacement was assigned once, never reshuffled");
            }
        }
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                Assertions.fail("timed out waiting for: " + what);
            }
            Thread.sleep(50);
        }
    }
}
