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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.FencedInstanceIdException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * How the adapter classifies what the Kafka client throws — {@code kafkaLLD §5.7}.
 *
 * <p>Everything here is about one boundary: which client failure becomes which {@code CommitOutcome}, and
 * which is not an outcome at all. The owner's behaviour is covered against {@code PumpedKafkaSource}
 * instead, and that fixture cannot cover this, because a fixture decides what to throw while this class
 * decides what a throw <em>means</em>. That gap is not theoretical — a routine wakeup was classified as a
 * structural failure and killed the process, with the owner's own test green, because
 * {@code WakeupException} is a {@code KafkaException} and therefore a {@code RuntimeException}.
 */
class KafkaConsumerSourcePortTest {

    private static final TopicPartition PARTITION = new TopicPartition("traffic", 0);
    private static final Duration BOUND = Duration.ofMillis(250);

    /**
     * A scripted consumer, configured at construction rather than stubbed per call.
     *
     * <p>{@code MockConsumer} is Kafka's own implementation, which is what makes it usable here: everything
     * the adapter does not script behaves exactly as the client does.
     */
    private static final class ScriptedCommitConsumer extends MockConsumer<String, byte[]> {
        private final RuntimeException syncFailure;
        private final RuntimeException asyncSubmissionFailure;
        private final Exception asyncCallbackFailure;
        private final List<Map<TopicPartition, OffsetAndMetadata>> syncCommits = new ArrayList<>();

        private ScriptedCommitConsumer(
            RuntimeException syncFailure,
            RuntimeException asyncSubmissionFailure,
            Exception asyncCallbackFailure
        ) {
            super("earliest");
            this.syncFailure = syncFailure;
            this.asyncSubmissionFailure = asyncSubmissionFailure;
            this.asyncCallbackFailure = asyncCallbackFailure;
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
            syncCommits.add(offsets);
            if (syncFailure != null) {
                throw syncFailure;
            }
        }

        @Override
        public synchronized void commitAsync(
            Map<TopicPartition, OffsetAndMetadata> offsets,
            OffsetCommitCallback callback
        ) {
            if (asyncSubmissionFailure != null) {
                throw asyncSubmissionFailure;
            }
            callback.onComplete(offsets, asyncCallbackFailure);
        }
    }

    private static KafkaConsumerSourcePort portThrowingFromSync(RuntimeException failure) {
        return new KafkaConsumerSourcePort(
            new ScriptedCommitConsumer(failure, null, null),
            Duration.ofSeconds(1)
        );
    }

    /**
     * {@code §5.7} lists a wakeup-interrupted commit as an unknown outcome, and separately lists what is
     * <em>not</em> an outcome: "Authorization failure, oversized offset metadata, an invalid commit offset
     * size, and any unrecognized commit failure are process-fatal". A wakeup is recognised and named, so it
     * must not reach that branch.
     *
     * <p>It leaves as {@code WakeupException} rather than as an outcome because {@code §5.4} makes the owner
     * the only boundary that may interpret a wakeup — it has to record that the commit spent the outstanding
     * one before mapping it.
     */
    @Test
    void aWakeupInterruptingACommitReachesTheOwnerRatherThanTheFatalBranch() {
        var port = portThrowingFromSync(new WakeupException());

        Assertions.assertThrows(
            WakeupException.class,
            () -> port.commitSync(Map.of(PARTITION, 11L), BOUND),
            "a wakeup must reach the owner; classifying it here makes a routine queued input fatal"
        );
    }

    @Test
    void aBoundedCommitThatRunsOutOfTimeIsAnUnknownOutcome() {
        var port = portThrowingFromSync(new TimeoutException("bound exhausted"));

        Assertions.assertEquals(
            KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN,
            port.commitSync(Map.of(PARTITION, 11L), BOUND)
        );
    }

    @Test
    void aLostGenerationIsStaleRatherThanRetriable() {
        Assertions.assertEquals(
            KafkaSourcePort.CommitOutcome.GENERATION_STALE,
            portThrowingFromSync(new CommitFailedException()).commitSync(Map.of(PARTITION, 11L), BOUND)
        );
        Assertions.assertEquals(
            KafkaSourcePort.CommitOutcome.GENERATION_STALE,
            portThrowingFromSync(new FencedInstanceIdException("fenced"))
                .commitSync(Map.of(PARTITION, 11L), BOUND)
        );
    }

    /**
     * {@code RebalanceInProgressException} keeps the generation, so the staged position is still this
     * consumer's to commit. Treating it as stale would discard progress for partitions kept across the
     * rebalance, which is what {@code §5.7}'s keep-and-re-offer rule exists to prevent.
     */
    @Test
    void aRebalanceInProgressIsRetriableBecauseTheGenerationSurvives() {
        Assertions.assertEquals(
            KafkaSourcePort.CommitOutcome.RETRIABLE,
            portThrowingFromSync(new RebalanceInProgressException("rejoining"))
                .commitSync(Map.of(PARTITION, 11L), BOUND)
        );
    }

    @Test
    void anAuthorizationFailureIsNotAnOutcomeAtAll() {
        var port = portThrowingFromSync(new TopicAuthorizationException("traffic"));

        var fatal = Assertions.assertThrows(
            IllegalStateException.class,
            () -> port.commitSync(Map.of(PARTITION, 11L), BOUND)
        );
        Assertions.assertInstanceOf(TopicAuthorizationException.class, fatal.getCause());
    }

    @Test
    void anAsynchronousRetriableFailureReachesTheCallbackAsRetriable() {
        var port = new KafkaConsumerSourcePort(
            new ScriptedCommitConsumer(null, null, new RetriableCommitFailedException("retry")),
            Duration.ofSeconds(1)
        );
        var resolutions = new ArrayList<KafkaSourcePort.CommitOutcome>();

        port.commitAsync(Map.of(PARTITION, 11L), resolutions::add);

        Assertions.assertEquals(List.of(KafkaSourcePort.CommitOutcome.RETRIABLE), resolutions);
    }

    /**
     * A submission the client refuses outright must still resolve exactly once.
     *
     * <p>{@code §5.7} allows one commit operation at a time. A submission that throws before registering a
     * callback would otherwise leave the source waiting for a resolution that cannot arrive, and "waiting
     * forever for one operation" is the same thing as never committing again.
     */
    @Test
    void anAsynchronousSubmissionRefusedBeforeRegistrationStillResolvesOnce() {
        var port = new KafkaConsumerSourcePort(
            new ScriptedCommitConsumer(null, new FencedInstanceIdException("fenced"), null),
            Duration.ofSeconds(1)
        );
        var resolutions = new ArrayList<KafkaSourcePort.CommitOutcome>();

        port.commitAsync(Map.of(PARTITION, 11L), resolutions::add);

        Assertions.assertEquals(
            List.of(KafkaSourcePort.CommitOutcome.GENERATION_STALE),
            resolutions,
            "a refused submission must resolve, or the one-in-flight slot is never released"
        );
    }

    @Test
    void anAcknowledgedCommitCarriesThePositionsItWasGiven() {
        var consumer = new ScriptedCommitConsumer(null, null, null);
        var port = new KafkaConsumerSourcePort(consumer, Duration.ofSeconds(1));

        Assertions.assertEquals(
            KafkaSourcePort.CommitOutcome.ACKNOWLEDGED,
            port.commitSync(Map.of(PARTITION, 11L), BOUND)
        );
        Assertions.assertEquals(
            Optional.of(11L),
            Optional.ofNullable(consumer.syncCommits.get(0).get(PARTITION)).map(OffsetAndMetadata::offset),
            "the offset committed is the next position to read, not the last one read"
        );
    }
}
