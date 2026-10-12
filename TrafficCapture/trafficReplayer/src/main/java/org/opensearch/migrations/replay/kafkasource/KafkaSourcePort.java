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

import lombok.NonNull;
import org.apache.kafka.common.TopicPartition;

/**
 * Isolates the Kafka client operations used by the source state machine from the state machine itself.
 *
 * <p>The port exposes polling, pause and resume, committed-position lookup, bounded and asynchronous commit,
 * and close. Returned records intentionally lack process-local generations; attaching current ownership is
 * an architectural decision made after polling, not Kafka-client metadata.</p>
 *
 * <p>Commit results preserve whether an operation was rejected before acceptance or may already have
 * reached the broker. Wakeup is deliberately outside this interface because it is synchronization for
 * escaping a poll, not a source-state operation, and must be governed by a separate phase-aware controller.</p>
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
    Map<TopicPartition, List<PolledKafkaRecord>> poll(Duration maximumWait);

    /**
     * Polls using the port's configured timeout.
     *
     * <p>The owner supplies a shorter bound while a commit retry is backing off, so Kafka polling itself is
     * the Kafka-aware wait for the retry deadline. Direct adapter callers that do not own such a deadline use
     * this convenience form.</p>
     */
    default Map<TopicPartition, List<PolledKafkaRecord>> poll() {
        return poll(Duration.ofNanos(Long.MAX_VALUE));
    }

    void pause(TopicPartition topicPartition);

    void resume(TopicPartition topicPartition);

    /** Kafka's committed position for a newly assigned partition, empty when it has none. */
    Optional<Long> committedPosition(TopicPartition topicPartition);

    /**
     * Submits the given durable commit points without waiting, for use in the ordinary loop.
     *
     * <p>Asynchronous because a synchronous commit here blocks for as long as the client retries internally
     * while the owner does not poll, which can exceed {@code max.poll.interval.ms} and provoke the rebalance
     * that a blocking commit is dangerous for in the first place ({@code kafkaLLD §5.7}). The loop polls every
     * iteration and a poll is what delivers the callback, so nothing extra is needed to make progress.
     *
     * @param onResolved invoked with the operation's outcome, on the Kafka thread, from within a later
     *                   {@code poll()}. Travels with the submission rather than being registered once, so
     *                   there is no mutable wiring and no question which submission a callback belongs to —
     *                   which is also why it carries no echo of the commit points: the caller already holds them,
     *                   along with the generation each belongs to, which this port has no knowledge of
     */
    AsyncCommitSubmission commitAsync(
        Map<TopicPartition, CommitPoint> commitPoints,
        Consumer<CommitResult> onResolved
    );

    /**
     * Whether the client accepted an asynchronous operation before this call returned.
     *
     * <p>A rejection is known not to have moved the broker position. An accepted operation resolves only
     * through its callback, except that an exception after acceptance may force the owner to resolve it as
     * unknown. Keeping acceptance separate from {@link CommitOutcome} is what prevents a local refusal from
     * being conflated with an operation that may already have reached the broker.
     */
    // The warning targets the compiler-generated canonical constructor of this public contract record.
    @SuppressWarnings("java:S100")
    record AsyncCommitSubmission(boolean accepted, CommitResult rejection) {
        public AsyncCommitSubmission {
            if (accepted == (rejection != null)) {
                throw new IllegalArgumentException(
                    "accepted submissions have no rejection outcome; rejected submissions require one"
                );
            }
        }

        public static AsyncCommitSubmission acceptedByClient() {
            return new AsyncCommitSubmission(true, null);
        }

        public static AsyncCommitSubmission rejectedBeforeAcceptance(@NonNull CommitResult rejection) {
            return new AsyncCommitSubmission(false, rejection);
        }
    }

    /**
     * Submits the given durable commit points and waits at most {@code bound} for the result.
     *
     * <p>Only for {@code onPartitionsRevoked}. Asynchronous submission cannot be used there because its
     * callback needs a later poll, and the generation is gone before that poll happens
     * ({@code kafkaLLD §5.7}). The bound is what stops the callback outliving its grace deadline: unbounded,
     * the client would block up to {@code default.api.timeout.ms} — 60s by default — against a grace interval
     * of one second, and overrunning the rebalance timeout fences the member and turns this graceful
     * revocation into a lost one.
     */
    CommitResult commitSync(Map<TopicPartition, CommitPoint> commitPoints, Duration bound);

    /**
     * One durable replay watermark committed atomically for a partition.
     *
     * <p>{@code nextPosition} retains Kafka's normal meaning: the next offset the group should read.
     * {@code replayedThroughLogAppendTimeMillis} is the broker timestamp of the immediately preceding
     * contiguously and durably replayed record. The adapter stores that timestamp in Kafka's offset metadata,
     * allowing diagnostics to measure temporal replay backlog without fetching record payloads.</p>
     */
    record CommitPoint(long nextPosition, long replayedThroughLogAppendTimeMillis) {
        public CommitPoint {
            if (nextPosition < 0) {
                throw new IllegalArgumentException("nextPosition must be non-negative");
            }
        }
    }

    /** Closes the Kafka client after orderly shutdown has drained and resolved its final commits. */
    void close();

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

    /**
     * A fixed-cardinality operational explanation for a failed commit.
     *
     * <p>This deliberately carries no exception message, broker, topic, partition, generation, or offset.
     * Those details remain in bounded logs while metrics aggregate safely across a fleet.</p>
     */
    enum CommitFailureClass {
        NONE,
        REBALANCE,
        COORDINATOR,
        TIMEOUT,
        TRANSPORT,
        WAKEUP,
        OTHER
    }

    /** One commit outcome together with its bounded operational failure classification. */
    record CommitResult(
        @NonNull CommitOutcome outcome,
        @NonNull CommitFailureClass failureClass
    ) {
        public CommitResult {
            if ((outcome == CommitOutcome.ACKNOWLEDGED)
                != (failureClass == CommitFailureClass.NONE)) {
                throw new IllegalArgumentException(
                    "acknowledged commits require NONE; failed commits require a failure class"
                );
            }
            if (outcome == CommitOutcome.LATE_CALLBACK) {
                throw new IllegalArgumentException(
                    "LATE_CALLBACK is assigned by the owner, not returned by a port"
                );
            }
        }

        public static CommitResult acknowledged() {
            return new CommitResult(CommitOutcome.ACKNOWLEDGED, CommitFailureClass.NONE);
        }

        public static CommitResult failed(
            @NonNull CommitOutcome outcome,
            @NonNull CommitFailureClass failureClass
        ) {
            return new CommitResult(outcome, failureClass);
        }
    }
}
