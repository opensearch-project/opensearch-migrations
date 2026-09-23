/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.kafka.common.TopicPartition;

/**
 * Every Kafka operation {@code KafkaSourceOwner} performs, and nothing else. Valid only on the Kafka thread.
 *
 * <p>The owner holds this rather than a {@code KafkaConsumer} for the same reason
 * {@code TargetConnectionOwner} holds a {@code TargetChannelPort}: the owner's logic — demand, pause
 * reasons, commit positions, the revocation sequence — is what needs proving, and it can be driven entirely
 * through these seven calls. A real consumer and the deterministic fixture supply the same interface.
 *
 * <p>Deliberately absent: {@code wakeup()} and anything that inspects queued source inputs. Wakeup is
 * {@link WakeupController}'s decision, not an operation the owner performs mid-loop, and inputs arrive
 * through {@link KafkaSourceInputQueue}.
 */
public interface KafkaSourcePort {

    /** Partitions Kafka currently considers assigned, which is what must be paused after a rebalance. */
    Set<TopicPartition> assignment();

    /**
     * One poll. Returns the records grouped by partition, empty when the poll produced none.
     *
     * <p>A failure here is fatal and must propagate: {@code kafkaLLD} allows no "empty success" that hides a
     * poll error, because an empty result is indistinguishable from a partition with nothing to read and
     * would silently stall the source instead of ending the process.
     */
    Map<TopicPartition, List<ApplicationKafkaRecord>> poll();

    void pause(TopicPartition topicPartition);

    void resume(TopicPartition topicPartition);

    /** Kafka's committed position for a newly assigned partition, empty when it has none. */
    Optional<Long> committedPosition(TopicPartition topicPartition);

    /**
     * Commits the given next-read positions. May carry several partitions in one operation.
     *
     * @return the outcome, which the owner records; commit outcomes never travel back to replay intake,
     *         which finished its record-processing decision before sending {@code RecordProcessingFinished}
     */
    CommitOutcome commit(Map<TopicPartition, Long> nextPositions);

    /** The distinctions {@code kafkaLLD §5.7} requires commit handling to keep apart. */
    enum CommitOutcome {
        /** The Kafka client accepted and acknowledged the operation. */
        ACKNOWLEDGED,
        /** The operation was rejected. */
        REJECTED,
        /** Ownership ended before the operation was submitted. */
        OWNERSHIP_ENDED_BEFORE_SUBMISSION,
        /** Submitted, but ownership ended with the broker outcome unknown. */
        OWNERSHIP_ENDED_OUTCOME_UNKNOWN,
        /** A callback arrived after local cleanup; diagnostic only. */
        LATE_CALLBACK
    }
}
