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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.tracing.KafkaSourceRootContext;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class KafkaSourceInputQueueTest {

    private static final PartitionGenerationId GENERATION =
        new PartitionGenerationId(new TopicPartition("traffic", 0), 1);

    private final AtomicInteger wakeups = new AtomicInteger();
    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(false, false);
    private final WakeupController wakeupController = newController(wakeups::incrementAndGet);
    private final KafkaSourceInputQueue queue = new KafkaSourceInputQueue(wakeupController);

    private WakeupController newController(Runnable issueWakeup) {
        // REBUILD-LIMBO-NOTE(G3): becomes RootReplayerContext.
        return new WakeupController(issueWakeup, new KafkaSourceRootContext(telemetry.openTelemetrySdk));
    }

    private static KafkaSourceInput request(long sequence) {
        return new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(GENERATION, sequence)
        );
    }

    /**
     * The value must be queued before the wakeup is issued, so a wakeup can never point at work the Kafka
     * thread cannot yet see. Observed by having the wakeup callback read the very queue being submitted to,
     * which requires a holder only because the queue and its controller are mutually referential.
     */
    @Test
    void theInputIsVisibleInTheQueueBeforeTheWakeupIsIssued() {
        var queueHolder = new AtomicReference<KafkaSourceInputQueue>();
        var sizeWhenWoken = new ArrayList<Integer>();
        var controller = newController(() -> sizeWhenWoken.add(queueHolder.get().size()));
        var observedQueue = new KafkaSourceInputQueue(controller);
        queueHolder.set(observedQueue);

        controller.enterPoll();
        observedQueue.submit(request(1));

        Assertions.assertEquals(
            List.of(1),
            sizeWhenWoken,
            "the wakeup fired while the queue was still empty, so it pointed at nothing"
        );
    }

    @Test
    void submissionWakesAPollingConsumerAndDrainingIsBoundedToWhatWasQueued() {
        wakeupController.enterPoll();
        queue.submit(request(1));
        queue.submit(request(2));

        Assertions.assertEquals(1, wakeups.get(), "coalesced to one wakeup for the single poll");
        Assertions.assertEquals(2, queue.size());

        var drained = queue.drain();

        Assertions.assertEquals(2, drained.size());
        Assertions.assertTrue(queue.isEmpty());
        Assertions.assertTrue(queue.drain().isEmpty());
    }

    @Test
    void pollRemovesInFifoOrder() {
        queue.submit(request(1));
        queue.submit(request(2));

        Assertions.assertEquals(request(1), queue.poll().orElseThrow());
        Assertions.assertEquals(request(2), queue.poll().orElseThrow());
        Assertions.assertTrue(queue.poll().isEmpty());
    }

    /**
     * A revocation callback waiting out its grace deadline must learn about commit and lifecycle inputs from
     * the queue signal, because it is protected from Kafka wakeup ({@code kafkaLLD §15.1}).
     */
    @Test
    void awaitInputWakesOnSubmissionAndOtherwiseHonoursTheMonotonicDeadline() throws Exception {
        // A duration, because that is what awaitInput takes: it cannot read the owner's clock, so the owner
        // converts its deadline and this measures only elapsed time (kafkaLLD §15.1, one source per deadline).
        var longEnoughToProveTheSignalDidIt = Duration.ofMinutes(5).toNanos();
        var waiterReturned = new CountDownLatch(1);
        var sawInput = new AtomicBoolean();
        var waiter = new Thread(() -> {
            try {
                sawInput.set(queue.awaitInput(longEnoughToProveTheSignalDidIt));
                waiterReturned.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        queue.submit(request(1));

        Assertions.assertTrue(
            waiterReturned.await(10, TimeUnit.SECONDS),
            "submission did not signal the waiting callback, so it would have sat out the whole interval"
        );
        Assertions.assertTrue(sawInput.get());
        waiter.join();
    }

    /**
     * A caller whose deadline has already passed asks for no wait at all, and still gets an honest answer about
     * what is queued rather than a blanket false.
     */
    @Test
    void awaitInputDoesNotWaitWhenNoTimeRemainsButStillReportsQueuedInput() throws Exception {
        Assertions.assertFalse(queue.awaitInput(0), "an empty queue with no time left has nothing to report");
        Assertions.assertTrue(queue.isEmpty());

        queue.submit(request(1));

        Assertions.assertTrue(
            queue.awaitInput(-1),
            "an input already queued must be reported even when the caller can no longer wait for one"
        );
    }

    /**
     * Answers whether the choice of input variant matters anywhere in the wakeup path: it does not. Neither
     * the queue nor the controller looks inside an input, so every variant must produce exactly the same
     * wakeup. Checked over all four rather than stated, so a future variant that somehow needed different
     * handling would fail here instead of being assumed equivalent.
     */
    @ParameterizedTest
    @MethodSource("everyInputVariant")
    void everyInputVariantWakesAPollingConsumerIdentically(KafkaSourceInput input) {
        var wakeups = new AtomicInteger();
        var controller = newController(wakeups::incrementAndGet);
        var variantQueue = new KafkaSourceInputQueue(controller);

        controller.enterPoll();
        variantQueue.submit(input);

        Assertions.assertEquals(1, wakeups.get(), () -> input.getClass().getSimpleName() + " issued no wakeup");
        Assertions.assertEquals(1, variantQueue.size());
        Assertions.assertTrue(controller.isWakeupOutstanding());
    }

    static List<KafkaSourceInput> everyInputVariant() {
        var recordId = new KafkaRecordId(GENERATION, 0);
        var variants = List.<KafkaSourceInput>of(
            new KafkaSourceInput.RequestNextPartitionBatch(new PartitionBatchRequestId(GENERATION, 1)),
            new KafkaSourceInput.RecordProcessingFinished(recordId),
            new KafkaSourceInput.GenerationCleanupFinished(GENERATION),
            new KafkaSourceInput.CaptureProtocolViolationDetected(recordId, "malformed envelope")
        );
        // Fails if a variant is added without being covered here, rather than silently testing a subset.
        Assertions.assertEquals(
            KafkaSourceInput.class.getPermittedSubclasses().length,
            variants.size(),
            "KafkaSourceInput gained a variant that this test does not cover"
        );
        return variants;
    }

    /** A refused submission must fail its caller rather than disappear: a lost input is a stuck record. */
    @Test
    void aClosedQueueRefusesSubmissionInsteadOfDroppingIt() {
        queue.submit(request(1));
        queue.close();

        Assertions.assertThrows(IllegalStateException.class, () -> queue.submit(request(2)));
        Assertions.assertEquals(
            1,
            queue.drain().size(),
            "already-queued inputs stay drainable so shutdown can finish them"
        );
    }
}
