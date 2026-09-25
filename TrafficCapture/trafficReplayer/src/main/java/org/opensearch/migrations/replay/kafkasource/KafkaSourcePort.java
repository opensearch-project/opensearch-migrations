/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.kafka.common.TopicPartition;

/**
 * Every Kafka operation {@code KafkaSourceOwner} performs, and nothing else. Valid only on the Kafka thread.
 *
 * <p>The owner holds this rather than a {@code KafkaConsumer} for the same reason
 * {@code TargetConnectionOwner} holds a {@code TargetChannelPort}: the owner's logic — demand, pause
 * reasons, commit positions, the revocation sequence — is what needs proving, and it can be driven entirely
 * through these calls. A real consumer and the deterministic fixture supply the same interface.
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
     * <p>Returns {@link PolledKafkaRecord} rather than a record carrying process-local identity, because
     * generations belong to {@code KafkaSourceOwner} alone ({@code kafkaLLD §5}) and the owner stamps them.</p>
     *
     * <p>A failure here is fatal and must propagate: {@code kafkaLLD} allows no "empty success" that hides a
     * poll error, because an empty result is indistinguishable from a partition with nothing to read and
     * would silently stall the source instead of ending the process.
     */
    Map<TopicPartition, List<PolledKafkaRecord>> poll();

    void pause(TopicPartition topicPartition);

    void resume(TopicPartition topicPartition);

    /** Kafka's committed position for a newly assigned partition, empty when it has none. */
    Optional<Long> committedPosition(TopicPartition topicPartition);

    /**
     * Submits the given next-read positions without waiting, for use in the ordinary loop.
     *
     * <p>Asynchronous because a synchronous commit here blocks for as long as the client retries internally
     * while the owner does not poll, which can exceed {@code max.poll.interval.ms} and provoke the rebalance
     * that a blocking commit is dangerous for in the first place ({@code kafkaLLD §5.7}). The loop polls every
     * iteration and a poll is what delivers the callback, so nothing extra is needed to make progress.
     *
     * @param onResolved invoked with the operation's outcome, on the Kafka thread, from within a later
     *                   {@code poll()}. Travels with the submission rather than being registered once, so
     *                   there is no mutable wiring and no question which submission a callback belongs to —
     *                   which is also why it carries no echo of the positions: the caller already holds them,
     *                   along with the generation each belongs to, which this port has no knowledge of
     */
    AsyncCommitSubmission commitAsync(
        Map<TopicPartition, Long> nextPositions,
        Consumer<CommitOutcome> onResolved
    );

    /**
     * Whether the client accepted an asynchronous operation before this call returned.
     *
     * <p>A rejection is known not to have moved the broker position. An accepted operation resolves only
     * through its callback, except that an exception after acceptance may force the owner to resolve it as
     * unknown. Keeping acceptance separate from {@link CommitOutcome} is what prevents a local refusal from
     * being conflated with an operation that may already have reached the broker.
     */
    record AsyncCommitSubmission(boolean accepted, CommitOutcome rejectionOutcome) {
        public AsyncCommitSubmission {
            if (accepted == (rejectionOutcome != null)) {
                throw new IllegalArgumentException(
                    "accepted submissions have no rejection outcome; rejected submissions require one"
                );
            }
        }

        public static AsyncCommitSubmission acceptedByClient() {
            return new AsyncCommitSubmission(true, null);
        }

        public static AsyncCommitSubmission rejectedBeforeAcceptance(CommitOutcome outcome) {
            return new AsyncCommitSubmission(false, java.util.Objects.requireNonNull(outcome, "outcome"));
        }
    }

    /**
     * Submits the given next-read positions and waits at most {@code bound} for the result.
     *
     * <p>Only for {@code onPartitionsRevoked}. Asynchronous submission cannot be used there because its
     * callback needs a later poll, and the generation is gone before that poll happens
     * ({@code kafkaLLD §5.7}). The bound is what stops the callback outliving its grace deadline: unbounded,
     * the client would block up to {@code default.api.timeout.ms} — 60s by default — against a grace interval
     * of one second, and overrunning the rebalance timeout fences the member and turns this graceful
     * revocation into a lost one.
     */
    CommitOutcome commitSync(Map<TopicPartition, Long> nextPositions, Duration bound);

    /**
     * The distinctions {@code kafkaLLD §5.7} requires commit handling to keep apart. They describe the
     * <strong>operation</strong>, never individual partitions: the per-partition error codes exist on the wire
     * and the client discards them, so a failure never says which position was not recorded.
     *
     * <p>Structurally invalid commits are deliberately absent. Authorization failure, oversized offset
     * metadata and an invalid offset size are not outcomes but process-fatal, because retrying cannot fix them
     * and reading on while never committing loses data at the next restart without saying so.
     */
    enum CommitOutcome {
        /** The client recorded the operation. */
        ACKNOWLEDGED,
        /** The client reports a failure it expects the caller to retry; the generation is intact. */
        RETRIABLE,
        /**
         * This member's generation or membership is no longer valid. The broker validates generation per
         * request rather than per partition, so this covers every partition in the operation and singles out
         * none of them.
         */
        GENERATION_STALE,
        /** A bounded wait elapsed, or a wakeup interrupted the call. May already have reached the broker. */
        OUTCOME_UNKNOWN,
        /**
         * The callback's generation is no longer held locally; diagnostic only.
         *
         * <p>Assigned by the owner rather than returned by a port: whether a generation is still held is the
         * owner's knowledge, not the client's. It is in this enum because {@code §5.7} lists it among the
         * distinctions commit handling keeps apart, and commit handling spans both.
         */
        LATE_CALLBACK
    }
}
