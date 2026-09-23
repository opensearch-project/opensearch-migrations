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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Exercises every wakeup path in {@link WakeupController} against a real Kafka broker, and verifies the
 * <em>timing</em> of each from spans and metrics rather than from wall-clock assertions in the test.
 *
 * <p>Why a real broker: the property under test is that {@code KafkaConsumer.wakeup()} actually shortens a
 * blocking {@code poll()}. A fake port returns immediately, so it can prove the controller's bookkeeping but
 * not the thing the bookkeeping exists for. The poll timeout here is deliberately long — 30 seconds — so a
 * broken wakeup shows up as a poll span lasting 30 seconds rather than as a test that merely passes slowly.
 *
 * <p>Why spans: "a queued input wakes a long poll promptly" is a statement about elapsed time, and the
 * {@code kafkaSourcePoll} span is where that elapsed time is recorded. Asserting on the span rather than on
 * {@code System.nanoTime()} in the test means the same measurement a production dashboard would show is the
 * one the test checks, so instrumentation drift breaks the test.
 *
 * <p>Each test prints its span timings, so a run is readable without a debugger.
 */
@Tag("isolatedTest")
@Slf4j
class WakeupAgainstRealKafkaTest {

    private static final Duration LONG_POLL = Duration.ofSeconds(30);
    /**
     * A wakeup should end a poll in milliseconds. This bound only has to be far below {@link #LONG_POLL} to
     * distinguish "woken" from "timed out"; it is not a performance assertion.
     */
    private static final Duration PROMPT = Duration.ofSeconds(5);

    private ConfluentKafkaContainer kafka;
    private InMemoryInstrumentationBundle telemetry;
    private KafkaConsumer<String, byte[]> consumer;
    private TopicPartition topicPartition;

    @BeforeEach
    void startBroker() {
        kafka = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);
        kafka.start();
        telemetry = new InMemoryInstrumentationBundle(true, true);

        var props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers());
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "wakeup-test");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props);
        topicPartition = new TopicPartition("wakeup-topic", 0);
        consumer.assign(Set.of(topicPartition));
        // Paused, so poll() blocks for its whole timeout unless something wakes it. That is the condition the
        // design cares about: "the Kafka source may sit in a long poll while every partition is paused".
        consumer.pause(Set.of(topicPartition));
    }

    @AfterEach
    void stopBroker() {
        if (consumer != null) {
            consumer.close(Duration.ofSeconds(5));
        }
        if (telemetry != null) {
            telemetry.close();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    private String brokers() {
        var bootstrap = kafka.getBootstrapServers();
        var schemeEnd = bootstrap.indexOf("://");
        return schemeEnd < 0 ? bootstrap : bootstrap.substring(schemeEnd + 3);
    }

    private WakeupController controllerFor(KafkaConsumer<String, byte[]> target) {
        return new WakeupController(target::wakeup, telemetry.openTelemetrySdk);
    }

    private KafkaSourceInputQueue queueFor(WakeupController controller) {
        return new KafkaSourceInputQueue(controller);
    }

    private static KafkaSourceInput anInput(TopicPartition topicPartition) {
        return new KafkaSourceInput.GenerationCleanupFinished(
            new PartitionGenerationId(topicPartition, 0)
        );
    }

    /**
     * The headline property: a queued input ends a 30-second paused poll promptly, and the poll span proves it
     * rather than the test timing itself.
     */
    @Test
    void aQueuedInputWakesALongPausedPollAndThePollSpanShowsIt() throws Exception {
        var controller = controllerFor(consumer);
        var queue = queueFor(controller);
        var pollThrew = new AtomicReference<Boolean>();

        var submitted = new CountDownLatch(1);
        var submitter = new Thread(() -> {
            try {
                // Long enough that the consumer is certainly inside poll(), short enough to keep the test brisk.
                Thread.sleep(500);
                queue.submit(anInput(topicPartition));
                submitted.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        submitter.start();

        controller.enterPoll();
        try {
            consumer.poll(LONG_POLL);
            pollThrew.set(false);
        } catch (WakeupException woken) {
            pollThrew.set(true);
        } finally {
            controller.leavePollAndConsumeWakeup();
        }
        Assertions.assertTrue(submitted.await(10, TimeUnit.SECONDS));
        submitter.join();

        Assertions.assertTrue(pollThrew.get(), "poll returned normally, so the wakeup never reached it");
        Assertions.assertFalse(queue.isEmpty(), "the input must be queued before the wakeup is issued");

        var pollDuration = reportAndReturnSingleSpanDuration(WakeupController.POLL_SPAN);
        Assertions.assertTrue(
            pollDuration.compareTo(PROMPT) < 0,
            () -> "poll ran for "
                + pollDuration.toMillis()
                + "ms against a "
                + LONG_POLL.toSeconds()
                + "s timeout, so the wakeup did not shorten it"
        );
        assertCounter(WakeupController.WAKEUPS_ISSUED, 1);
        assertCounter(WakeupController.WAKEUPS_COALESCED, 0);
        assertCounter(WakeupController.WAKEUPS_DEFERRED, 0);
    }

    /** Several submissions during one poll must produce one wakeup, not one each. */
    @Test
    void manySubmissionsDuringOnePollCoalesceIntoASingleWakeup() throws Exception {
        var controller = controllerFor(consumer);
        var queue = queueFor(controller);

        var submitter = new Thread(() -> {
            try {
                Thread.sleep(500);
                for (var i = 0; i < 5; i++) {
                    queue.submit(anInput(topicPartition));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        submitter.start();

        controller.enterPoll();
        try {
            consumer.poll(LONG_POLL);
            Assertions.fail("poll should have been woken");
        } catch (WakeupException expected) {
            // The wakeup arrived, which is the point.
        } finally {
            controller.leavePollAndConsumeWakeup();
        }
        submitter.join();

        Assertions.assertEquals(5, queue.size(), "all five inputs must be queued");
        var pollDuration = reportAndReturnSingleSpanDuration(WakeupController.POLL_SPAN);
        Assertions.assertTrue(pollDuration.compareTo(PROMPT) < 0);
        assertCounter(WakeupController.WAKEUPS_ISSUED, 1);
        assertCounter(WakeupController.WAKEUPS_COALESCED, 4);
    }

    /**
     * A wakeup must not be delivered during a rebalance callback. Verified by the timing relationship the
     * spans record: the callback span ends before any wakeup can shorten the surrounding poll, and the
     * deferred wakeup is issued only on the way out.
     */
    @Test
    void aWakeupDuringARebalanceCallbackIsDeferredUntilTheCallbackReturns() throws Exception {
        var controller = controllerFor(consumer);
        var queue = queueFor(controller);
        var callbackWork = Duration.ofMillis(750);

        controller.enterPoll();
        controller.enterRebalanceCallback();
        // Stand in for real callback work. The submission lands mid-callback, which is the case under test.
        var submitterRan = new CountDownLatch(1);
        var submitter = new Thread(() -> {
            try {
                Thread.sleep(250);
                queue.submit(anInput(topicPartition));
                submitterRan.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        submitter.start();
        Thread.sleep(callbackWork.toMillis());

        Assertions.assertTrue(submitterRan.await(10, TimeUnit.SECONDS));
        assertCounter(WakeupController.WAKEUPS_DEFERRED, 1);
        assertCounter(
            WakeupController.WAKEUPS_ISSUED,
            0
        );

        controller.leaveRebalanceCallback();
        assertCounter(WakeupController.WAKEUPS_ISSUED, 1);

        // The poll is now woken, so it returns promptly even though its timeout is 30 seconds.
        try {
            consumer.poll(LONG_POLL);
            Assertions.fail("poll should have been woken by the deferred wakeup");
        } catch (WakeupException expected) {
            // expected
        } finally {
            controller.leavePollAndConsumeWakeup();
        }
        submitter.join();

        var callbackSpan = singleSpan(WakeupController.REBALANCE_CALLBACK_SPAN);
        var pollSpan = singleSpan(WakeupController.POLL_SPAN);
        reportSpans();

        Assertions.assertTrue(
            durationOf(callbackSpan).compareTo(Duration.ofMillis(500)) > 0,
            "the callback did not actually span the submission, so nothing was deferred across it"
        );
        Assertions.assertTrue(
            Boolean.TRUE.equals(
                callbackSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey(
                    "issuedDeferredWakeupOnExit"))
            ),
            "the callback span should record that it issued the deferred wakeup as it left"
        );
        Assertions.assertTrue(
            callbackSpan.getEndEpochNanos() <= pollSpan.getEndEpochNanos(),
            "the callback must finish inside the poll it ran within"
        );
        Assertions.assertTrue(durationOf(pollSpan).compareTo(LONG_POLL) < 0);
    }

    /**
     * A commit must not be interrupted by a wakeup. The protected-operation span is the window during which a
     * submission produces no wakeup at all.
     */
    @Test
    void aWakeupDuringACommitIsDeferredAndTheCommitCompletes() throws Exception {
        var controller = controllerFor(consumer);
        var queue = queueFor(controller);
        var port = new KafkaConsumerSourcePort(consumer, LONG_POLL, Map.of());

        controller.enterProtectedOperation();
        KafkaSourcePort.CommitOutcome outcome;
        try {
            queue.submit(anInput(topicPartition));
            assertCounter(WakeupController.WAKEUPS_DEFERRED, 1);
            assertCounter(WakeupController.WAKEUPS_ISSUED, 0);
            outcome = port.commit(Map.of(topicPartition, 0L));
        } finally {
            controller.leaveProtectedOperation();
        }

        Assertions.assertEquals(
            KafkaSourcePort.CommitOutcome.ACKNOWLEDGED,
            outcome,
            "the commit was disturbed, which is what deferring the wakeup exists to prevent"
        );
        assertCounter(WakeupController.WAKEUPS_ISSUED, 0);

        // The deferred wakeup is issued when the next poll starts, so that poll does not wait on it.
        controller.enterPoll();
        try {
            consumer.poll(LONG_POLL);
            Assertions.fail("poll should have been woken by the wakeup deferred from the commit");
        } catch (WakeupException expected) {
            // expected
        } finally {
            controller.leavePollAndConsumeWakeup();
        }

        assertCounter(WakeupController.WAKEUPS_ISSUED, 1);
        var pollDuration = reportAndReturnSingleSpanDuration(WakeupController.POLL_SPAN);
        Assertions.assertTrue(pollDuration.compareTo(PROMPT) < 0);
    }

    /** With nothing submitted, the poll runs to its timeout — the control case for every assertion above. */
    @Test
    void anUndisturbedPausedPollRunsToItsTimeout() {
        var controller = controllerFor(consumer);
        var shortTimeout = Duration.ofSeconds(2);

        controller.enterPoll();
        try {
            consumer.poll(shortTimeout);
        } finally {
            controller.leavePollAndConsumeWakeup();
        }

        var pollDuration = reportAndReturnSingleSpanDuration(WakeupController.POLL_SPAN);
        Assertions.assertTrue(
            pollDuration.compareTo(shortTimeout.minusMillis(250)) >= 0,
            () -> "poll returned after " + pollDuration.toMillis() + "ms without being woken, so a wakeup"
                + " assertion elsewhere could pass for the wrong reason"
        );
        assertCounter(WakeupController.WAKEUPS_ISSUED, 0);
    }

    // ------------------------------------------------------------------ span and metric helpers

    private static Duration durationOf(SpanData span) {
        return Duration.ofNanos(span.getEndEpochNanos() - span.getStartEpochNanos());
    }

    private SpanData singleSpan(String name) {
        var matching = telemetry.getFinishedSpans()
            .stream()
            .filter(span -> span.getName().equals(name))
            .collect(Collectors.toList());
        Assertions.assertEquals(1, matching.size(), () -> "expected exactly one " + name + " span");
        return matching.get(0);
    }

    private Duration reportAndReturnSingleSpanDuration(String name) {
        reportSpans();
        return durationOf(singleSpan(name));
    }

    /** Prints every captured span with its duration, so the run output shows the timings it asserted on. */
    private void reportSpans() {
        var spans = new ArrayList<>(telemetry.getFinishedSpans());
        spans.sort((a, b) -> Long.compare(a.getStartEpochNanos(), b.getStartEpochNanos()));
        var report = new StringBuilder("\n  span timings:");
        for (var span : spans) {
            report.append(String.format("%n    %-32s %8.1f ms  %s",
                span.getName(), durationOf(span).toNanos() / 1_000_000.0, span.getAttributes()));
        }
        report.append("\n  counters:");
        for (var metric : List.of(
            WakeupController.WAKEUPS_ISSUED,
            WakeupController.WAKEUPS_COALESCED,
            WakeupController.WAKEUPS_DEFERRED
        )) {
            report.append(String.format("%n    %-32s %d", metric, counter(metric)));
        }
        log.info(report.toString());
    }

    private long counter(String metricName) {
        return InMemoryInstrumentationBundle.getMetricValueOrZero(
            telemetry.getFinishedMetrics(),
            metricName
        );
    }

    private void assertCounter(String metricName, long expected) {
        Assertions.assertEquals(expected, counter(metricName), metricName);
    }
}
