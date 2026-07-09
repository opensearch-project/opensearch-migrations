package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.http.retries.NoRetryEvaluatorFactory;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression test for the cancelled-session permit leak bug.
 *
 * After a Kafka consumer group rebalance, sessions are marked cancelled=true. Requests
 * already scheduled on Netty event loop timers (with far-future timestamps due to
 * scheduling lag) hold their TrafficStreamLimiter permits indefinitely. This starves the
 * consumer thread (which needs permits to process new records), preventing Kafka polls
 * and causing group eviction.
 *
 * The fix ensures that cancelConnection() drains all queued timer futures exceptionally,
 * and that the isCancelled() check in submitUnorderedWorkToEventLoop prevents cascading
 * sorter callbacks from running. The whenComplete handler on each request future then
 * releases the permit.
 *
 * Without the fix, this test times out because permits are never released.
 */
class CancelledSessionPermitLeakTest extends InstrumentationTest {

    private static final int PERMIT_COUNT = 5;

    private ClientConnectionPool pool;
    private RequestSenderOrchestrator orchestrator;
    private TrafficStreamLimiter limiter;

    @BeforeEach
    void setUp() {
        pool = new ClientConnectionPool(
            (el, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no-op channel"),
            "test-pool", 1
        );
        orchestrator = new RequestSenderOrchestrator(pool, (session, ctx) -> null);
        limiter = new TrafficStreamLimiter(PERMIT_COUNT);
    }

    @AfterEach
    void tearDown() throws Exception {
        limiter.close();
        pool.shutdownNow().get();
    }

    /**
     * Schedules several requests at far-future timestamps (holding limiter permits),
     * then cancels the connection. All permits must be released back to the limiter
     * within a short timeout.
     *
     * Before fix: far-future timer futures never fire, so the request futures never
     * complete, so doneProcessing() is never called, and the semaphore stays drained.
     */
    @Test
    @Timeout(10)
    void cancelledSession_releasesAllPermits() throws Exception {
        var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-leak", 0)
            .getChannelKeyContext();

        // Get the session so we can synchronize with its event loop later
        var session = pool.getCachedSession(channelKeyCtx, 0);

        int numRequests = PERMIT_COUNT;
        List<TrackedFuture<String, ?>> requestFutures = new ArrayList<>();
        AtomicInteger permitsReleased = new AtomicInteger(0);

        // Schedule requests at far-future timestamps. Each request acquires a permit
        // from the limiter (simulating what TrafficReplayerCore does) and attaches a
        // whenComplete handler to release it.
        for (int i = 0; i < numRequests; i++) {
            var reqCtx = rootContext.getTestConnectionRequestContext("conn-leak", i);

            // Acquire a permit (mirrors sendRequestAfterGoingThroughWorkQueue)
            limiter.liveTrafficStreamCostGate.acquire(1);

            var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1}));
            var future = orchestrator.scheduleRequest(
                reqCtx.getReplayerRequestKey(),
                reqCtx,
                Instant.now().plusSeconds(60), // far future - won't fire naturally
                Duration.ZERO,
                ByteBufListProducer.of(packets),
                new NoRetryEvaluatorFactory.NoRetryVisitor()
            );

            // Attach permit-release handler (mirrors TrafficReplayerCore line 177-179)
            future.whenComplete(
                (v, t) -> {
                    limiter.liveTrafficStreamCostGate.release(1);
                    permitsReleased.incrementAndGet();
                },
                () -> "releasing permit after request completes"
            );

            requestFutures.add(future);
        }

        // Wait for the event loop to process all submissions
        session.eventLoop.submit(() -> {}).sync();

        // Verify all permits have been consumed
        Assertions.assertEquals(0, limiter.liveTrafficStreamCostGate.availablePermits(),
            "All permits should be held by scheduled requests");

        // Cancel the connection (simulates partition reassignment)
        pool.cancelConnection(channelKeyCtx, 0);

        // Wait for permits to be released (with the fix, this should complete quickly)
        boolean allPermitsReleased = limiter.liveTrafficStreamCostGate.tryAcquire(
            PERMIT_COUNT, 2, TimeUnit.SECONDS);

        if (allPermitsReleased) {
            // Release them back so the assertion below works cleanly
            limiter.liveTrafficStreamCostGate.release(PERMIT_COUNT);
        }

        Assertions.assertEquals(PERMIT_COUNT, limiter.liveTrafficStreamCostGate.availablePermits(),
            "All " + PERMIT_COUNT + " permits must be released after cancelConnection(). " +
            "Without the fix, far-future timer futures never fire, so permits leak indefinitely, " +
            "starving the Kafka consumer thread and causing group eviction.");

        Assertions.assertEquals(numRequests, permitsReleased.get(),
            "Every request future should have completed (exceptionally) releasing its permit");
    }

    /**
     * Exercises the isCancelled() check in submitUnorderedWorkToEventLoop
     * (RequestSenderOrchestrator line 290). Marks the session cancelled BEFORE scheduling
     * requests with past timestamps. The sorter callback sees isCancelled()==true and returns
     * a failed future without executing the task.
     */
    @Test
    @Timeout(10)
    void cancelledSessionBeforeSchedule_failsFastOnSorterCallback() throws Exception {
        var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-pre-cancel", 0)
            .getChannelKeyContext();
        var session = pool.getCachedSession(channelKeyCtx, 0);

        // Mark session cancelled BEFORE scheduling work
        session.setCancelled(true);

        AtomicInteger completedExceptionally = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            var reqCtx = rootContext.getTestConnectionRequestContext("conn-pre-cancel", i);
            var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1}));
            var future = orchestrator.scheduleRequest(
                reqCtx.getReplayerRequestKey(),
                reqCtx,
                Instant.now().minusSeconds(1),
                Duration.ZERO,
                ByteBufListProducer.of(packets),
                new NoRetryEvaluatorFactory.NoRetryVisitor()
            );
            future.whenComplete(
                (v, t) -> {
                    if (t != null) {
                        completedExceptionally.incrementAndGet();
                    }
                },
                () -> "tracking exceptional completion"
            );
        }

        session.eventLoop.submit(() -> {}).sync();

        Assertions.assertEquals(3, completedExceptionally.get(),
            "All requests should complete exceptionally via the"
                + " isCancelled() check in submitUnorderedWorkToEventLoop");
    }

    /**
     * Schedules a request with a short delay, lets the sorter process it (not cancelled yet),
     * then cancels the session BEFORE the Netty send-schedule timer fires. When the timer
     * fires and the send path attempts to open a channel, getChannelFutureInActiveState sees
     * cancelled==true and rejects the request with an exception.
     */
    @Test
    @Timeout(10)
    void cancelAfterSorterButBeforeTimerFire_failsOnChannelAcquire() throws Exception {
        var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-race", 0)
            .getChannelKeyContext();
        var session = pool.getCachedSession(channelKeyCtx, 0);

        var completionLatch = new CountDownLatch(1);

        // Schedule at +500ms — long enough to cancel before the timer fires
        var reqCtx = rootContext.getTestConnectionRequestContext("conn-race", 0);
        var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1}));
        var future = orchestrator.scheduleRequest(
            reqCtx.getReplayerRequestKey(),
            reqCtx,
            Instant.now().plusMillis(500),
            Duration.ZERO,
            ByteBufListProducer.of(packets),
            new NoRetryEvaluatorFactory.NoRetryVisitor()
        );
        future.whenComplete(
            (v, t) -> completionLatch.countDown(),
            () -> "signalling completion latch"
        );

        // Wait for event loop to process the submission (sorter fires, timer queued)
        session.eventLoop.submit(() -> {}).sync();

        // Cancel the session WITHOUT calling drainWithCancellation — simulates the race
        // window between setCancelled(true) and drain. The timer will fire naturally
        // at +500ms and find isCancelled()==true.
        session.setCancelled(true);

        // Wait for the future to complete (timer fires, cancelled check rejects)
        Assertions.assertTrue(
            completionLatch.await(5, TimeUnit.SECONDS),
            "Request future should complete within timeout");
    }

    /**
     * Exercises the transformation-phase timer drain path. scheduleWork creates a standalone
     * Netty timer NOT registered in session.schedule. Without drainTransformationTimers(),
     * these far-future timers would hold permits indefinitely after cancelConnection().
     *
     * This test calls orchestrator.scheduleWork directly (the same path ReplayEngine uses for
     * transformation scheduling) with a far-future timestamp, then cancels via cancelConnection.
     * The transformation timer must be drained immediately, releasing the permit.
     */
    @Test
    @Timeout(10)
    void cancelConnection_drainsTransformationTimers() throws Exception {
        var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-transform", 0)
            .getChannelKeyContext();
        var session = pool.getCachedSession(channelKeyCtx, 0);

        var completionLatch = new CountDownLatch(1);

        // Acquire a permit (mirrors scheduleTransformationWork path)
        limiter.liveTrafficStreamCostGate.acquire(1);

        // Schedule transformation work at far-future — this is the path ReplayEngine uses.
        // Timer is standalone (not in session.schedule), tracked in
        // pendingTransformationTimers.
        var reqCtx = rootContext.getTestConnectionRequestContext("conn-transform", 0);
        var workFuture = orchestrator.scheduleWork(
            reqCtx,
            Instant.now().plusSeconds(60),
            () -> TextTrackedFuture.completedFuture(null, () -> "transformation result")
        );
        workFuture.whenComplete(
            (v, t) -> {
                if (t != null) {
                    limiter.liveTrafficStreamCostGate.release(1);
                }
                completionLatch.countDown();
            },
            () -> "releasing permit on transformation cancel"
        );

        // Wait for event loop to process the timer scheduling
        session.eventLoop.submit(() -> {}).sync();

        Assertions.assertFalse(session.pendingTransformationTimers.isEmpty(),
            "Transformation timer should be tracked before cancel");
        Assertions.assertEquals(PERMIT_COUNT - 1,
            limiter.liveTrafficStreamCostGate.availablePermits(),
            "One permit should be held by the pending transformation");

        // cancelConnection drains both transformation timers and schedule timers
        pool.cancelConnection(channelKeyCtx, 0);

        // Wait for completion (drain is synchronous for transformation timers)
        Assertions.assertTrue(
            completionLatch.await(5, TimeUnit.SECONDS),
            "Work future should complete after cancelConnection");
        Assertions.assertEquals(PERMIT_COUNT,
            limiter.liveTrafficStreamCostGate.availablePermits(),
            "Permit must be released after transformation timer drain");
    }
}
