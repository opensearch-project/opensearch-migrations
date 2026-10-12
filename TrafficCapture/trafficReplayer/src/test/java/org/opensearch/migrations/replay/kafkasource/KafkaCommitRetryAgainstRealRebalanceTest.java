/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Forces a real cooperative group rebalance while deterministic retriable commit failures are being delivered.
 *
 * <p>The broker owns assignment and callback timing; only the commit response is scripted, because reliably
 * coercing a broker into a particular transient offset-commit error would make this a timing lottery. The
 * assertion is the owner's safety property: rapid polls and real revocation callbacks cannot turn those
 * responses into an unbounded commit-attempt loop.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
class KafkaCommitRetryAgainstRealRebalanceTest {
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    @Container
    private final ConfluentKafkaContainer kafka =
        new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    private static final class RetriableCommitPort implements KafkaSourcePort {
        private final KafkaConsumerSourcePort delegate;
        private final AtomicInteger asyncCommitAttempts = new AtomicInteger();
        private final AtomicInteger syncCommitAttempts = new AtomicInteger();
        private Consumer<CommitResult> pendingResolution;
        private boolean failCommits = true;

        private RetriableCommitPort(KafkaConsumer<String, byte[]> consumer) {
            delegate = new KafkaConsumerSourcePort(consumer, POLL_TIMEOUT);
        }

        @Override
        public Set<TopicPartition> assignment() {
            return delegate.assignment();
        }

        @Override
        public Map<TopicPartition, List<PolledKafkaRecord>> poll(Duration maximumWait) {
            if (pendingResolution != null) {
                var resolution = pendingResolution;
                pendingResolution = null;
                resolution.accept(failCommits
                    ? CommitResult.failed(
                        CommitOutcome.RETRIABLE,
                        CommitFailureClass.REBALANCE
                    )
                    : CommitResult.acknowledged());
            }
            return delegate.poll(maximumWait);
        }

        @Override
        public void pause(TopicPartition topicPartition) {
            delegate.pause(topicPartition);
        }

        @Override
        public void resume(TopicPartition topicPartition) {
            delegate.resume(topicPartition);
        }

        @Override
        public Optional<Long> committedPosition(TopicPartition topicPartition) {
            return delegate.committedPosition(topicPartition);
        }

        @Override
        public AsyncCommitSubmission commitAsync(
            Map<TopicPartition, CommitPoint> commitPoints,
            Consumer<CommitResult> onResolved
        ) {
            if (pendingResolution != null) {
                throw new IllegalStateException("more than one commit was accepted in flight");
            }
            asyncCommitAttempts.incrementAndGet();
            pendingResolution = onResolved;
            return AsyncCommitSubmission.acceptedByClient();
        }

        @Override
        public CommitResult commitSync(
            Map<TopicPartition, CommitPoint> commitPoints,
            Duration bound
        ) {
            syncCommitAttempts.incrementAndGet();
            return failCommits
                ? CommitResult.failed(
                    CommitOutcome.RETRIABLE,
                    CommitFailureClass.REBALANCE
                )
                : CommitResult.acknowledged();
        }

        private void acknowledgeCommits() {
            failCommits = false;
        }

        private int commitAttempts() {
            return asyncCommitAttempts.get() + syncCommitAttempts.get();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    void forcedRebalanceKeepsCommitAttemptRateBounded() throws Exception {
        var topic = "commit-backoff-rebalance-" + System.nanoTime();
        createTopic(topic, 2);
        publishOneRecordPerPartition(topic);

        var group = "commit-backoff-group-" + System.nanoTime();
        var telemetry = new InMemoryInstrumentationBundle(false, true);
        var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
        var firstConsumer = new KafkaConsumer<String, byte[]>(
            consumerProperties(group, "first")
        );
        var secondConsumer = new AtomicReference<KafkaConsumer<String, byte[]>>();
        var stopSecond = new AtomicBoolean();
        var secondStarted = new CountDownLatch(1);
        var revocationObserved = new CountDownLatch(1);
        Thread secondThread = null;
        KafkaSourceOwner ownerForCleanup = null;
        RetriableCommitPort portForCleanup = null;
        KafkaSourceInputQueue sourceInputsForCleanup = null;
        ReplayIntakeInputQueue intakeInputsForCleanup = null;
        try {
            var wakeupController = new WakeupController(firstConsumer::wakeup, rootContext);
            var sourceInputs = new KafkaSourceInputQueue(wakeupController);
            var intakeInputs = new ReplayIntakeInputQueue();
            var port = new RetriableCommitPort(firstConsumer);
            var owner = new KafkaSourceOwner(
                port,
                sourceInputs,
                intakeInputs,
                wakeupController,
                Duration.ofMillis(200),
                System::nanoTime,
                GraceIntervalWait.blockingOn(sourceInputs, System::nanoTime),
                rootContext.getKafkaCommitStateMetrics(),
                KafkaSourceOwner.RecordContextFactory.NONE,
                violation -> {
                    throw new KafkaSourceOwner.CaptureProtocolViolation(violation);
                },
                KafkaSourceOwner.DEFAULT_HEARTBEAT_INTERVAL,
                KafkaSourceOwner.randomizedExponentialCommitRetryDelay(
                    Duration.ofMillis(100),
                    Duration.ofSeconds(5),
                    0.0,
                    () -> 0.0
                )
            );
            ownerForCleanup = owner;
            portForCleanup = port;
            sourceInputsForCleanup = sourceInputs;
            intakeInputsForCleanup = intakeInputs;
            firstConsumer.subscribe(
                List.of(topic),
                listenerFor(owner, revocationObserved)
            );

            var records = awaitRecords(owner, intakeInputs, 2);
            records.forEach(recordId ->
                sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(recordId))
            );
            owner.runOnce();
            Assertions.assertEquals(1, port.asyncCommitAttempts.get());

            secondThread = new Thread(() -> {
                try (var consumer = new KafkaConsumer<String, byte[]>(
                    consumerProperties(group, "second")
                )) {
                    secondConsumer.set(consumer);
                    consumer.subscribe(List.of(topic));
                    secondStarted.countDown();
                    while (!stopSecond.get()) {
                        consumer.poll(POLL_TIMEOUT);
                    }
                } catch (WakeupException stopping) {
                    if (!stopSecond.get()) {
                        throw stopping;
                    }
                }
            }, "forced-rebalance-second-consumer");
            secondThread.start();
            Assertions.assertTrue(secondStarted.await(10, TimeUnit.SECONDS));

            var exerciseUntil = System.nanoTime() + Duration.ofMillis(900).toNanos();
            while (System.nanoTime() - exerciseUntil < 0) {
                owner.runOnce();
            }

            Assertions.assertTrue(
                port.asyncCommitAttempts.get() <= 4,
                () -> "100/200/400ms backoff permits at most four ordinary attempts in 900ms, but observed "
                    + port.asyncCommitAttempts.get()
            );
            Assertions.assertTrue(
                port.syncCommitAttempts.get() <= 1,
                () -> "revocation may add at most one terminal attempt, but observed "
                    + port.syncCommitAttempts.get()
            );
            Assertions.assertTrue(
                port.commitAttempts() <= 5,
                () -> "four backoff-paced attempts plus one terminal attempt were expected, but observed "
                    + port.commitAttempts()
            );
            var rebalanceDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (revocationObserved.getCount() > 0
                && System.nanoTime() - rebalanceDeadline < 0) {
                owner.runOnce();
            }
            Assertions.assertTrue(
                revocationObserved.getCount() == 0,
                "the second member must force a real revocation callback"
            );
            Assertions.assertTrue(
                port.syncCommitAttempts.get() <= 1,
                () -> "a real revocation must not create a terminal retry loop, but observed "
                    + port.syncCommitAttempts.get() + " synchronous attempts"
            );
            Assertions.assertTrue(
                port.commitAttempts() <= 8,
                () -> "exponential backoff must bound attempts while waiting for a real rebalance, but observed "
                    + port.commitAttempts()
            );
            Assertions.assertTrue(
                port.commitAttempts() >= 1,
                "the rate assertion needs at least one failed commit attempt"
            );
        } finally {
            try {
                if (ownerForCleanup != null
                    && portForCleanup != null
                    && sourceInputsForCleanup != null
                    && intakeInputsForCleanup != null) {
                    portForCleanup.acknowledgeCommits();
                    ownerForCleanup.beginOrderlyShutdown();
                    var shutdownInputs = sourceInputsForCleanup;
                    var shutdownIntake = intakeInputsForCleanup;
                    var closeDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (!ownerForCleanup.isOrderlyShutdownReadyToClose()
                        && System.nanoTime() - closeDeadline < 0) {
                        acknowledgePendingGenerationCleanup(shutdownIntake, shutdownInputs);
                        ownerForCleanup.runOnce();
                    }
                    Assertions.assertTrue(
                        ownerForCleanup.isOrderlyShutdownReadyToClose(),
                        "real-Kafka test owner must drain through the production shutdown path"
                    );
                    ownerForCleanup.closeAfterOrderlyShutdown();
                } else {
                    firstConsumer.close(Duration.ofSeconds(5));
                }
            } finally {
                stopSecond.set(true);
                var consumer = secondConsumer.get();
                if (consumer != null) {
                    consumer.wakeup();
                }
                if (secondThread != null) {
                    secondThread.join(TimeUnit.SECONDS.toMillis(10));
                }
                telemetry.close();
            }
        }
    }

    private static void acknowledgePendingGenerationCleanup(
        ReplayIntakeInputQueue intakeInputs,
        KafkaSourceInputQueue sourceInputs
    ) throws InterruptedException {
        while (intakeInputs.size() > 0) {
            var input = intakeInputs.take();
            if (input instanceof ReplayIntakeInput.GracefulGenerationCancellation graceful) {
                sourceInputs.submit(
                    new KafkaSourceInput.GenerationCleanupFinished(graceful.generation())
                );
            } else if (input instanceof ReplayIntakeInput.ForceGenerationCancellation force) {
                sourceInputs.submit(
                    new KafkaSourceInput.GenerationCleanupFinished(force.generation())
                );
            }
        }
    }

    private static ConsumerRebalanceListener listenerFor(
        KafkaSourceOwner owner,
        CountDownLatch revocationObserved
    ) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    revocationObserved.countDown();
                }
                try {
                    owner.onPartitionsRevoked(partitions);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                owner.onPartitionsAssigned(partitions);
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                revocationObserved.countDown();
                owner.onPartitionsLost(partitions);
            }
        };
    }

    private static List<KafkaRecordId> awaitRecords(
        KafkaSourceOwner owner,
        ReplayIntakeInputQueue intakeInputs,
        int expectedCount
    ) throws Exception {
        var records = new ArrayList<KafkaRecordId>();
        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (records.size() < expectedCount && System.nanoTime() - deadline < 0) {
            owner.runOnce();
            while (intakeInputs.size() > 0) {
                var input = intakeInputs.take();
                if (input instanceof ReplayIntakeInput.PartitionRecordBatch batch) {
                    batch.records().forEach(record -> records.add(record.recordId()));
                }
            }
        }
        Assertions.assertEquals(expectedCount, records.size());
        return records;
    }

    private void createTopic(String topic, int partitions) throws Exception {
        try (var admin = Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getBootstrapServers()
        ))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                .all()
                .get(30, TimeUnit.SECONDS);
        }
    }

    private void publishOneRecordPerPartition(String topic) throws Exception {
        try (var producer = new KafkaProducer<String, byte[]>(
            producerProperties()
        )) {
            producer.send(new ProducerRecord<>(topic, 0, "key-0", new byte[] {0}))
                .get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, 1, "key-1", new byte[] {1}))
                .get(10, TimeUnit.SECONDS);
        }
    }

    private Properties consumerProperties(String group, String clientId) {
        var properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.setProperty(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer"
        );
        properties.setProperty(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArrayDeserializer"
        );
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "6000");
        properties.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "500");
        properties.setProperty(
            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"
        );
        return properties;
    }

    private Properties producerProperties() {
        var properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.setProperty(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringSerializer"
        );
        properties.setProperty(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArraySerializer"
        );
        return properties;
    }
}
