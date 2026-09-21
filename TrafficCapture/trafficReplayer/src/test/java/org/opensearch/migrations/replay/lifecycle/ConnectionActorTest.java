package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.replay.testing.TestEventLoop;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.tracing.ConnectionActorMetrics;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConnectionActorTest extends InstrumentationTest {
    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void preparationMayFinishOutOfOrderButExecutionCannot() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        var firstPreparation = new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var secondPreparation = new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var first = actor.admitRequest(request(0), Instant.EPOCH, firstPreparation).toCompletableFuture();
        var second = actor.admitRequest(request(1), Instant.EPOCH, secondPreparation).toCompletableFuture();

        secondPreparation.complete(new PreparationOutcome.Prepared<>(new TestPrepared("second")));
        mailbox.runUntilIdle();
        Assertions.assertTrue(exchange.executed.isEmpty());

        firstPreparation.complete(new PreparationOutcome.Prepared<>(new TestPrepared("first")));
        mailbox.runUntilIdle();
        Assertions.assertEquals(List.of("first"), exchange.executed);

        exchange.completeNext(new TargetOutcome.Succeeded<>("first-response"));
        mailbox.runUntilIdle();
        Assertions.assertEquals(List.of("first", "second"), exchange.executed);

        exchange.completeNext(new TargetOutcome.Succeeded<>("second-response"));
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(TargetOutcome.Succeeded.class, first.join());
        Assertions.assertInstanceOf(TargetOutcome.Succeeded.class, second.join());
    }

    @Test
    void actorUsesOneHeadTimerAndNeverSendsEarly() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        CompletableFuture<PreparationOutcome<TestPrepared>> prepared = CompletableFuture.completedFuture(
            new PreparationOutcome.Prepared<>(new TestPrepared("request"))
        );
        actor.admitRequest(request(0), Instant.ofEpochSecond(10), prepared);

        mailbox.runUntilIdle();
        Assertions.assertTrue(exchange.executed.isEmpty());
        Assertions.assertEquals(1, mailbox.pendingTimers());

        mailbox.advance(Duration.ofSeconds(9));
        Assertions.assertTrue(exchange.executed.isEmpty());
        mailbox.advance(Duration.ofSeconds(1));
        Assertions.assertEquals(List.of("request"), exchange.executed);
    }

    @Test
    void abortCancelsQueuedWorkWithoutExecutingItAndWaitsForTargetAbort() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        var first = actor.admitRequest(
            request(0),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(new TestPrepared("first")))
        ).toCompletableFuture();
        var secondPrepared = new TestPrepared("second");
        var second = actor.admitRequest(
            request(1),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(secondPrepared))
        ).toCompletableFuture();
        mailbox.runUntilIdle();

        var termination = actor.abort(
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("rebalance")
        ).toCompletableFuture();
        mailbox.runUntilIdle();
        Assertions.assertFalse(termination.isDone());
        Assertions.assertInstanceOf(TargetOutcome.Cancelled.class, second.join());
        Assertions.assertEquals(1, secondPrepared.closeCount);
        Assertions.assertEquals(List.of("first"), exchange.executed);

        exchange.abortCompletion.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(TargetOutcome.Cancelled.class, first.join());
        Assertions.assertInstanceOf(SessionOutcome.Aborted.class, termination.join());

        exchange.completeNext(new TargetOutcome.Succeeded<>("late-response"));
        mailbox.runUntilIdle();
        Assertions.assertEquals(1, exchange.prepared.get(0).closeCount);
    }

    @Test
    void abortReportsActiveAndQueuedPreparedCleanupFailures() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        var activeCleanupFailure = new AssertionError("active cleanup failed");
        var queuedCleanupFailure = new AssertionError("queued cleanup failed");
        var activePrepared = new TestPrepared("active", activeCleanupFailure);
        var queuedPrepared = new TestPrepared("queued", queuedCleanupFailure);
        var active = actor.admitRequest(
            request(0),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(activePrepared))
        ).toCompletableFuture();
        var queued = actor.admitRequest(
            request(1),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(queuedPrepared))
        ).toCompletableFuture();
        mailbox.runUntilIdle();

        var termination = actor.abort(
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("rebalance")
        ).toCompletableFuture();
        mailbox.runUntilIdle();
        exchange.abortCompletion.complete(null);
        mailbox.runUntilIdle();

        Assertions.assertInstanceOf(TargetOutcome.Cancelled.class, active.join());
        Assertions.assertInstanceOf(TargetOutcome.Cancelled.class, queued.join());
        var failed = Assertions.assertInstanceOf(SessionOutcome.Failed.class, termination.join());
        Assertions.assertSame(activeCleanupFailure, failed.cause());
        Assertions.assertArrayEquals(
            new Throwable[] { queuedCleanupFailure },
            failed.cause().getSuppressed()
        );
        Assertions.assertEquals(1, activePrepared.closeCount);
        Assertions.assertEquals(1, queuedPrepared.closeCount);
    }

    @Test
    void orderedCloseRunsAfterRequestsAndCallerCannotCancelTermination() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        actor.admitRequest(
            request(0),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(new TestPrepared("request")))
        );
        var close = actor.admitClose(Instant.EPOCH).toCompletableFuture();
        var callerView = actor.termination().toCompletableFuture();
        callerView.cancel(false);
        mailbox.runUntilIdle();

        Assertions.assertFalse(close.isDone());
        Assertions.assertFalse(actor.termination().toCompletableFuture().isDone());
        exchange.completeNext(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();

        Assertions.assertEquals(1, exchange.closeCalls);
        Assertions.assertInstanceOf(SessionOutcome.Closed.class, close.join());
        Assertions.assertInstanceOf(SessionOutcome.Closed.class, actor.termination().toCompletableFuture().join());
    }

    @Test
    void lateAdmissionCancelsPreparationWithoutExecutingIt() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        actor.admitClose(Instant.EPOCH);
        mailbox.runUntilIdle();

        var preparation = new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var request = actor.admitRequest(request(0), Instant.EPOCH, preparation).toCompletableFuture();
        mailbox.runUntilIdle();

        Assertions.assertTrue(preparation.isCancelled());
        Assertions.assertInstanceOf(TargetOutcome.Cancelled.class, request.join());
        Assertions.assertTrue(exchange.executed.isEmpty());
    }

    @Test
    void abortRemainsAuthoritativeWhenOrderedCloseCompletesDuringDrain() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        exchange.closeCompletion = new CompletableFuture<>();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        var close = actor.admitClose(Instant.EPOCH).toCompletableFuture();
        mailbox.runUntilIdle();

        var termination = actor.abort(
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("rebalance")
        ).toCompletableFuture();
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(SessionOutcome.Aborted.class, close.join());

        exchange.closeCompletion.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertFalse(termination.isDone());

        exchange.abortCompletion.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(SessionOutcome.Aborted.class, termination.join());
    }

    @Test
    void lateOrderedCloseCompletionCannotMutateTerminatedActor() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        exchange.closeCompletion = new CompletableFuture<>();
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        actor.admitClose(Instant.EPOCH);
        mailbox.runUntilIdle();

        var termination = actor.abort(
            AbortReason.SOURCE_REASSIGNMENT,
            new CancellationException("rebalance")
        ).toCompletableFuture();
        mailbox.runUntilIdle();
        exchange.abortCompletion.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(SessionOutcome.Aborted.class, termination.join());

        exchange.closeCompletion.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(SessionOutcome.Aborted.class, actor.termination().toCompletableFuture().join());
    }

    @Test
    void recordsAuthoritativeQueueWaitActiveAndAbortState() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var nanoTime = new AtomicLong();
        var actor = new ConnectionActor<>(
            session(),
            mailbox,
            exchange,
            rootContext.getConnectionActorMetrics(),
            nanoTime::get
        );
        var firstPreparation = new CompletableFuture<PreparationOutcome<TestPrepared>>();
        actor.admitRequest(request(0), Instant.ofEpochSecond(10), firstPreparation);
        actor.admitRequest(
            request(1),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(new TestPrepared("second")))
        );
        mailbox.runUntilIdle();

        assertLongSum(ConnectionActorMetrics.MetricNames.QUEUED_COMMANDS, 2);
        assertHeadWait(ConnectionActor.HeadWaitReason.SCHEDULED_START, 1);

        mailbox.advance(Duration.ofSeconds(10));
        assertHeadWait(ConnectionActor.HeadWaitReason.SCHEDULED_START, 0);
        assertHeadWait(ConnectionActor.HeadWaitReason.PREPARATION, 1);

        firstPreparation.complete(new PreparationOutcome.Prepared<>(new TestPrepared("first")));
        mailbox.runUntilIdle();
        assertHeadWait(ConnectionActor.HeadWaitReason.PREPARATION, 0);
        assertHeadWait(ConnectionActor.HeadWaitReason.ACTIVE_EXCHANGE, 1);

        nanoTime.set(5_000_000);
        exchange.completeNext(new TargetOutcome.Succeeded<>("first-response"));
        mailbox.runUntilIdle();
        Assertions.assertEquals(List.of("first", "second"), exchange.executed);
        assertLongSum(ConnectionActorMetrics.MetricNames.QUEUED_COMMANDS, 1);

        actor.abort(AbortReason.SOURCE_REASSIGNMENT, new CancellationException("rebalance"));
        mailbox.runUntilIdle();
        assertHeadWait(ConnectionActor.HeadWaitReason.ACTIVE_EXCHANGE, 0);
        assertPendingAbortChild(1);

        nanoTime.set(8_000_000);
        exchange.abortCompletion.complete(null);
        mailbox.runUntilIdle();

        assertLongSum(ConnectionActorMetrics.MetricNames.QUEUED_COMMANDS, 0);
        assertPendingAbortChild(0);
        assertHistogram(ConnectionActorMetrics.MetricNames.ACTIVE_DURATION, 2, 8.0);
        assertHistogram(ConnectionActorMetrics.MetricNames.ABORT_DURATION, 1, 3.0);
    }

    private void assertHeadWait(ConnectionActor.HeadWaitReason reason, long expected) {
        assertAttributedLongSum(
            ConnectionActorMetrics.MetricNames.HEAD_WAIT,
            ConnectionActorMetrics.REASON_ATTRIBUTE,
            reason.metricLabel(),
            expected
        );
    }

    private void assertPendingAbortChild(long expected) {
        assertAttributedLongSum(
            ConnectionActorMetrics.MetricNames.PENDING_ABORT_CHILD,
            ConnectionActorMetrics.CHILD_ATTRIBUTE,
            ConnectionActor.AbortChild.TARGET_EXCHANGE.metricLabel(),
            expected
        );
    }

    private void assertLongSum(String metricName, long expected) {
        var metric = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(candidate -> candidate.getName().equals(metricName))
            .findFirst();
        if (metric.isEmpty()) {
            Assertions.assertEquals(0, expected, "an absent metric point represents zero");
            return;
        }
        var point = metric.orElseThrow()
            .getLongSumData()
            .getPoints()
            .stream()
            .findFirst();
        if (point.isEmpty()) {
            Assertions.assertEquals(0, expected, "an absent metric point represents zero");
            return;
        }
        Assertions.assertEquals(expected, point.orElseThrow().getValue());
    }

    private void assertAttributedLongSum(
        String metricName,
        io.opentelemetry.api.common.AttributeKey<String> attribute,
        String value,
        long expected
    ) {
        var metric = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(candidate -> candidate.getName().equals(metricName))
            .findFirst();
        if (metric.isEmpty()) {
            Assertions.assertEquals(0, expected, "an absent metric point represents zero");
            return;
        }
        var point = metric.orElseThrow()
            .getLongSumData()
            .getPoints()
            .stream()
            .filter(candidate -> value.equals(candidate.getAttributes().get(attribute)))
            .findFirst();
        if (point.isEmpty()) {
            Assertions.assertEquals(0, expected, "an absent metric point represents zero");
            return;
        }
        Assertions.assertEquals(expected, point.orElseThrow().getValue());
    }

    private void assertHistogram(String metricName, long expectedCount, double expectedSum) {
        var point = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(metric -> metric.getName().equals(metricName))
            .findFirst()
            .orElseThrow()
            .getHistogramData()
            .getPoints()
            .stream()
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(expectedCount, point.getCount());
        Assertions.assertEquals(expectedSum, point.getSum());
    }

    private static ConnectionSessionKey session() {
        return new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1);
    }

    private static ReplayRequestId request(int index) {
        return new ReplayRequestId(session(), index);
    }

    /** Proves replayer LLD §4 and R19: a rejected required owner submission is process-fatal. */
    @Test
    void rejectedImmediateSubmissionReachesFatalHandler() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var actor = new ConnectionActor<>(
            session(),
            mailbox,
            new TestExchange(),
            ConnectionActor.Metrics.NOOP,
            fatalFailures::add
        );
        mailbox.rejectNewTasks();

        actor.admitClose(Instant.EPOCH);

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(fatalFailures.get(0).getMessage().contains("ordered close admission"));
        Assertions.assertInstanceOf(
            java.util.concurrent.RejectedExecutionException.class,
            fatalFailures.get(0).getCause()
        );
    }

    /** Proves replayer LLD §4 and R19 for the actor's scheduled-head submission. */
    @Test
    void rejectedScheduledSubmissionReachesFatalHandler() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var exchange = new TestExchange();
        var actor = new ConnectionActor<>(
            session(),
            mailbox,
            exchange,
            ConnectionActor.Metrics.NOOP,
            fatalFailures::add
        );
        actor.admitRequest(
            request(0),
            Instant.EPOCH.plusSeconds(10),
            CompletableFuture.completedFuture(
                new PreparationOutcome.Prepared<>(new TestPrepared("request"))
            )
        );
        mailbox.rejectNewTasks();

        mailbox.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(fatalFailures.get(0).getMessage().contains("scheduled head start"));
        Assertions.assertTrue(exchange.executed.isEmpty());
    }

    @Test
    void settledRequestDoesNotDependOnASecondMailboxDelivery() {
        var mailbox = new TestEventLoop();
        var exchange = new TestExchange();
        var prepared = new TestPrepared("request");
        var actor = new ConnectionActor<>(session(), mailbox, exchange);
        var request = actor.admitRequest(
            request(0),
            Instant.EPOCH,
            CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(prepared))
        ).toCompletableFuture();
        mailbox.runUntilIdle();

        exchange.completeNext(new TargetOutcome.Succeeded<>("response"));
        mailbox.runNext();
        mailbox.dropAcceptedWork();

        Assertions.assertInstanceOf(TargetOutcome.Succeeded.class, request.join());
        Assertions.assertEquals(1, prepared.closeCount);
    }

    private static final class TestPrepared implements AutoCloseable {
        private final String name;
        private final AssertionError closeFailure;
        private int closeCount;

        private TestPrepared(String name) {
            this(name, null);
        }

        private TestPrepared(String name, AssertionError closeFailure) {
            this.name = name;
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class TestExchange implements ConnectionActor.TargetExchange<TestPrepared, String> {
        private final List<String> executed = new ArrayList<>();
        private final List<TestPrepared> prepared = new ArrayList<>();
        private final Queue<CompletableFuture<TargetOutcome<String>>> active = new ArrayDeque<>();
        private final CompletableFuture<Void> abortCompletion = new CompletableFuture<>();
        private CompletableFuture<Void> closeCompletion = CompletableFuture.completedFuture(null);
        private int closeCalls;

        @Override
        public CompletableFuture<TargetOutcome<String>> execute(TestPrepared preparedRequest) {
            executed.add(preparedRequest.name);
            prepared.add(preparedRequest);
            var completion = new CompletableFuture<TargetOutcome<String>>();
            active.add(completion);
            return completion;
        }

        @Override
        public CompletableFuture<Void> close() {
            closeCalls++;
            return closeCompletion;
        }

        @Override
        public CompletableFuture<Void> abort(CancellationException cause) {
            return abortCompletion;
        }

        void completeNext(TargetOutcome<String> outcome) {
            active.remove().complete(outcome);
        }
    }

}
