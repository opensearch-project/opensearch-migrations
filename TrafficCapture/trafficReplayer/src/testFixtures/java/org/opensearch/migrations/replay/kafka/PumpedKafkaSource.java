/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.opensearch.migrations.replay.intake.ReplayIntakeInput.PartitionRecordBatch;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;

import org.apache.kafka.common.TopicPartition;

/**
 * Deterministic Kafka-source harness driven only by explicit {@link #runOnce()} calls.
 *
 * <p>The harness owns scripts and observations, while a pluggable driver owns source state
 * transitions. S8 can connect the production Kafka-source owner through that driver without
 * rewriting record scripts or observation assertions.
 *
 * <p>The messages it carries are the production ones — {@link KafkaSourceInput} in,
 * {@link PartitionRecordBatch} out, {@link ApplicationKafkaRecord} inside. It previously declared
 * its own copies of the identities and of the input family, which meant a driver written against
 * the fixture proved nothing about a driver written against the real types, and the real
 * {@code CaptureProtocolViolationDetected} carried a diagnostic the copy silently dropped.
 *
 * <p>{@link Observation} and {@link PauseReason} are the harness's own, because they describe what
 * a test watches rather than what the source exchanges. The design's requirement that the three
 * pause reasons stay independent ({@code kafkaLLD §17.4}) is why the reason is recorded on each
 * pause instead of being collapsed into a boolean.
 */
public final class PumpedKafkaSource {
    public enum PauseReason {
        BATCH_DEMAND,
        PRIOR_GENERATION_CLEANUP,
        REVOCATION_OR_SHUTDOWN
    }

    public sealed interface Observation permits
        WakeupRequested,
        PartitionPaused,
        PartitionResumed,
        BatchDelivered,
        CommitSubmitted,
        DriverFailed {}

    public record WakeupRequested() implements Observation {}

    public record PartitionPaused(TopicPartition topicPartition, PauseReason reason) implements Observation {}

    public record PartitionResumed(TopicPartition topicPartition) implements Observation {}

    public record BatchDelivered(PartitionRecordBatch batch) implements Observation {}

    public record CommitSubmitted(Map<TopicPartition, Long> nextOffsets) implements Observation {
        public CommitSubmitted {
            nextOffsets = Map.copyOf(nextOffsets);
        }
    }

    public record DriverFailed(Throwable failure) implements Observation {}

    @FunctionalInterface
    public interface SourceOwnerDriver {
        void runOnce(DriverPort port) throws Exception;
    }

    public interface DriverPort {
        Optional<KafkaSourceInput> pollInput();

        Optional<List<ApplicationKafkaRecord>> pollKafka();

        void pause(TopicPartition topicPartition, PauseReason reason);

        void resume(TopicPartition topicPartition);

        void deliver(PartitionRecordBatch batch);

        void submitCommit(Map<TopicPartition, Long> nextOffsets);
    }

    private final SourceOwnerDriver driver;
    private final Deque<KafkaSourceInput> inputs = new ArrayDeque<>();
    private final Deque<List<ApplicationKafkaRecord>> scriptedBatches = new ArrayDeque<>();
    private final List<Observation> observations = new ArrayList<>();
    private boolean wakeupPending;
    private boolean running;
    private Throwable failure;

    public PumpedKafkaSource(SourceOwnerDriver driver) {
        this.driver = Objects.requireNonNull(driver);
    }

    public void submit(KafkaSourceInput input) {
        requireHealthy();
        inputs.add(Objects.requireNonNull(input));
        if (!wakeupPending && !running) {
            wakeupPending = true;
            observations.add(new WakeupRequested());
        }
    }

    public void scriptBatch(Collection<ApplicationKafkaRecord> records) {
        requireHealthy();
        var batch = List.copyOf(records);
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("Use no scripted batch to represent an empty poll");
        }
        scriptedBatches.add(batch);
    }

    public void runOnce() {
        requireHealthy();
        if (running) {
            throw new AssertionError("PumpedKafkaSource cannot be pumped recursively");
        }
        wakeupPending = false;
        running = true;
        try {
            driver.runOnce(new Port());
        } catch (Throwable t) {
            failure = t;
            observations.add(new DriverFailed(t));
            throw new AssertionError("Source-owner driver failed", t);
        } finally {
            running = false;
        }
    }

    public List<Observation> observations() {
        return List.copyOf(observations);
    }

    public void assertExhausted() {
        requireHealthy();
        if (!inputs.isEmpty() || !scriptedBatches.isEmpty()) {
            throw new AssertionError(
                "PumpedKafkaSource not exhausted: inputs="
                    + inputs.size()
                    + ", batches="
                    + scriptedBatches.size()
            );
        }
    }

    private void requireHealthy() {
        if (failure != null) {
            throw new AssertionError("PumpedKafkaSource already failed", failure);
        }
    }

    private final class Port implements DriverPort {
        @Override
        public Optional<KafkaSourceInput> pollInput() {
            return Optional.ofNullable(inputs.poll());
        }

        @Override
        public Optional<List<ApplicationKafkaRecord>> pollKafka() {
            return Optional.ofNullable(scriptedBatches.poll());
        }

        @Override
        public void pause(TopicPartition topicPartition, PauseReason reason) {
            observations.add(new PartitionPaused(
                Objects.requireNonNull(topicPartition),
                Objects.requireNonNull(reason)
            ));
        }

        @Override
        public void resume(TopicPartition topicPartition) {
            observations.add(new PartitionResumed(Objects.requireNonNull(topicPartition)));
        }

        @Override
        public void deliver(PartitionRecordBatch batch) {
            observations.add(new BatchDelivered(Objects.requireNonNull(batch)));
        }

        @Override
        public void submitCommit(Map<TopicPartition, Long> nextOffsets) {
            var orderedCopy = new LinkedHashMap<TopicPartition, Long>();
            nextOffsets.forEach((partition, offset) -> {
                Objects.requireNonNull(partition);
                Objects.requireNonNull(offset);
                if (offset < 0) {
                    throw new IllegalArgumentException("commit offset must not be negative");
                }
                orderedCopy.put(partition, offset);
            });
            observations.add(new CommitSubmitted(orderedCopy));
        }
    }
}
