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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.FencedInstanceIdException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;

/**
 * {@link KafkaSourcePort} over a real {@code KafkaConsumer}.
 *
 * <p>Holds no source state: generations, demand and commit positions all belong to
 * {@code KafkaSourceOwner}. This only translates, which is what keeps the owner's logic provable without a
 * broker.
 */
public final class KafkaConsumerSourcePort implements KafkaSourcePort {

    private final Consumer<String, byte[]> consumer;
    private final Duration pollTimeout;
    private final Map<TopicPartition, PartitionGenerationId> generations;

    /**
     * @param generations the owner's current generation per partition, read at poll time so a record is
     *                    stamped with the generation that was active when it arrived
     */
    public KafkaConsumerSourcePort(
        Consumer<String, byte[]> consumer,
        Duration pollTimeout,
        Map<TopicPartition, PartitionGenerationId> generations
    ) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.pollTimeout = Objects.requireNonNull(pollTimeout, "pollTimeout");
        this.generations = Objects.requireNonNull(generations, "generations");
    }

    @Override
    public Set<TopicPartition> assignment() {
        return consumer.assignment();
    }

    /**
     * One poll, grouped by partition.
     *
     * <p>{@code WakeupException} propagates rather than being caught here: the owner's loop is the only
     * controlled boundary that may interpret it as "inspect the source-input queue", and swallowing it into
     * an empty result is indistinguishable from a partition with nothing to read
     * ({@code kafkaLLD §5.4}).
     */
    @Override
    public Map<TopicPartition, List<ApplicationKafkaRecord>> poll() {
        var polled = consumer.poll(pollTimeout);
        var byPartition = new LinkedHashMap<TopicPartition, List<ApplicationKafkaRecord>>();
        for (var topicPartition : polled.partitions()) {
            var generation = generations.get(topicPartition);
            if (generation == null) {
                throw new IllegalStateException(
                    "poll returned records for " + topicPartition + " which has no current generation"
                );
            }
            var records = new ArrayList<ApplicationKafkaRecord>();
            for (var kafkaRecord : polled.records(topicPartition)) {
                records.add(new ApplicationKafkaRecord(
                    new KafkaRecordId(generation, kafkaRecord.offset()),
                    kafkaRecord.timestamp(),
                    kafkaRecord.serializedValueSize(),
                    decode(kafkaRecord.value(), topicPartition, kafkaRecord.offset())
                ));
            }
            byPartition.put(topicPartition, List.copyOf(records));
        }
        return byPartition;
    }

    private static CaptureRecord decode(byte[] value, TopicPartition topicPartition, long offset) {
        try {
            return CaptureRecord.parseFrom(value);
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            throw new IllegalStateException(
                "Kafka record at " + topicPartition + "@" + offset + " is not a CaptureRecord envelope",
                notAnEnvelope
            );
        }
    }

    @Override
    public void pause(TopicPartition topicPartition) {
        consumer.pause(Set.of(topicPartition));
    }

    @Override
    public void resume(TopicPartition topicPartition) {
        consumer.resume(Set.of(topicPartition));
    }

    @Override
    public Optional<Long> committedPosition(TopicPartition topicPartition) {
        var committed = consumer.committed(Set.of(topicPartition)).get(topicPartition);
        return committed == null ? Optional.empty() : Optional.of(committed.offset());
    }

    @Override
    public void commitAsync(
        Map<TopicPartition, Long> nextPositions,
        BiConsumer<Map<TopicPartition, Long>, CommitOutcome> onResolved
    ) {
        var submitted = Map.copyOf(nextPositions);
        consumer.commitAsync(toOffsets(submitted), (offsets, failure) ->
            onResolved.accept(submitted, classifyAsync(failure))
        );
    }

    /**
     * Unlike {@code commitSync}, {@code commitAsync} does not exhaust retriable errors itself — it wraps them
     * in {@code RetriableCommitFailedException} whose own message is "You should retry committing the latest
     * consumed offsets". So the retriable case reaches us here and only here.
     */
    private static CommitOutcome classifyAsync(Exception failure) {
        if (failure == null) {
            return CommitOutcome.ACKNOWLEDGED;
        }
        if (failure instanceof RetriableCommitFailedException) {
            return CommitOutcome.RETRIABLE;
        }
        if (isGenerationStale(failure)) {
            return CommitOutcome.GENERATION_STALE;
        }
        throw structurallyInvalid(failure);
    }

    /**
     * Bounded so the call cannot outlive the grace deadline the caller derived {@code bound} from.
     *
     * <p>The catch wraps the single Kafka call because there is one operation and therefore one failure. It is
     * deliberately not a loop with a catch per partition: the client offers no per-partition result to catch,
     * and classifying partitions by anything available here — the current assignment, say — would be a guess
     * dressed as a distinction.
     */
    @Override
    public CommitOutcome commitSync(Map<TopicPartition, Long> nextPositions, Duration bound) {
        try {
            consumer.commitSync(toOffsets(nextPositions), bound);
            return CommitOutcome.ACKNOWLEDGED;
        } catch (WakeupException | TimeoutException mayHaveReachedTheBroker) {
            return CommitOutcome.OUTCOME_UNKNOWN;
        } catch (RuntimeException failure) {
            if (isGenerationStale(failure)) {
                return CommitOutcome.GENERATION_STALE;
            }
            throw structurallyInvalid(failure);
        }
    }

    /**
     * The three the client uses to say this member's generation or membership is gone.
     * {@code CommitFailedException}'s own javadoc is explicit that "the commit cannot generally be retried",
     * which is what separates these from {@code RetriableCommitFailedException}.
     */
    private static boolean isGenerationStale(Throwable failure) {
        return failure instanceof CommitFailedException
            || failure instanceof RebalanceInProgressException
            || failure instanceof FencedInstanceIdException;
    }

    /**
     * Everything left is unfixable by retrying — authorization, oversized offset metadata, an invalid offset
     * size, or something unrecognised. Every genuinely transient error is absorbed by the client before it
     * reaches here, so a failure arriving at this point is structural.
     */
    private static IllegalStateException structurallyInvalid(Throwable failure) {
        return new IllegalStateException(
            "commit failed for a reason retrying cannot fix; continuing to read without committing"
                + " would lose this progress at the next restart",
            failure
        );
    }

    private static Map<TopicPartition, OffsetAndMetadata> toOffsets(Map<TopicPartition, Long> nextPositions) {
        return nextPositions.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue())));
    }
}
