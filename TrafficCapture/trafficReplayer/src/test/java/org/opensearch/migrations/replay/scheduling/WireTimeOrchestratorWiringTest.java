package org.opensearch.migrations.replay.scheduling;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.http.retries.NoRetryEvaluatorFactory;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test for the wire-time dependency tracker wiring through
 * {@link RequestSenderOrchestrator}. The orchestrator's multiplexed branch must hold a
 * strictly-serial successor's dispatch until the predecessor's response-received future
 * has fired, not just until the successor's own atTime arrives.
 *
 * <p>Scenario: two requests A and B on the same source connection.
 * <ul>
 *   <li>A: source wire-time req [T+0..T+10ms], resp [T+10..T+20ms]</li>
 *   <li>B: source wire-time req start T+25ms, strictly after A's response</li>
 * </ul>
 *
 * <p>Both requests are scheduled with the same atTime (so without wire-time gating
 * they would dispatch simultaneously). The {@link BlockingPacketConsumer} for each request
 * lets the test control exactly when each request's response is allowed to land. The
 * test verifies:
 * <ol>
 *   <li>A's consumeBytes fires immediately (gate completed; first request).</li>
 *   <li>B's consumeBytes does NOT fire while A's finalizeRequest is still pending.</li>
 *   <li>After A's finalizeRequest completes, B's consumeBytes fires.</li>
 * </ol>
 *
 * <p>If the orchestrator wiring is broken (gate not installed, registered on the wrong
 * session, or wrong predecessor selection), B fires immediately and assertion (2) trips.
 *
 * <p>This is the live counterpart to {@link SessionDependencyTrackerTest} which only
 * exercises the pure tracker logic.
 */
@Slf4j
class WireTimeOrchestratorWiringTest extends InstrumentationTest {

    /** Time given to a racing dispatch to surface before asserting it didn't fire. */
    private static final Duration RACE_QUIESCE = Duration.ofMillis(200);

    /** Per-request consumer that gates {@code consumeBytes} and {@code finalizeRequest} on
     *  test-controlled semaphores so the orchestrator timing can be observed deterministically. */
    static class BlockingPacketConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {
        final long id;
        final Semaphore consumeBytesGate = new Semaphore(0);
        final Semaphore finalizeGate = new Semaphore(0);
        final CompletableFuture<Long> consumeBytesFiredAtNanos = new CompletableFuture<>();
        final CompletableFuture<Long> finalizeFiredAtNanos = new CompletableFuture<>();

        BlockingPacketConsumer(long id) { this.id = id; }

        @Override
        public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
            return new TextTrackedFuture<>(CompletableFuture.supplyAsync(() -> {
                consumeBytesFiredAtNanos.complete(System.nanoTime());
                try {
                    consumeBytesGate.acquire();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
                return (Void) null;
            }), () -> "BlockingPacketConsumer[" + id + "].consumeBytes");
        }

        @Override
        public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
            return new TextTrackedFuture<>(CompletableFuture.supplyAsync(() -> {
                finalizeFiredAtNanos.complete(System.nanoTime());
                try {
                    finalizeGate.acquire();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
                return new AggregatedRawResponse(null, 0, Duration.ZERO, null, null);
            }), () -> "BlockingPacketConsumer[" + id + "].finalizeRequest");
        }
    }

    @Test
    @Timeout(30)
    void multiplexedSerialSuccessor_blocksUntilPredecessorResponseReceived() throws Exception {
        // Source-side wire-time anchors: B starts strictly after A's response.
        var T0 = Instant.parse("2025-01-01T00:00:00Z");
        var aWireTimes = new WireTimeAnchors(
            T0,                          // A.requestFirstFrame
            T0.plusMillis(10),           // A.requestLastFrame
            T0.plusMillis(10),           // A.responseFirstFrame
            T0.plusMillis(20));          // A.responseLastFrame
        var bWireTimes = new WireTimeAnchors(
            T0.plusMillis(25),           // B.requestFirstFrame: strictly after A's response
            T0.plusMillis(30),
            T0.plusMillis(30),
            T0.plusMillis(40));

        var clientConnectionPool = TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
            new URI("http://localhost"),
            false,
            1,
            "WireTimeOrchestratorWiringTest serial pool"
        );

        var consumers = new HashMap<Long, BlockingPacketConsumer>();
        consumers.put(0L, new BlockingPacketConsumer(0));
        consumers.put(1L, new BlockingPacketConsumer(1));

        var orchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (replaySession, ctx) -> consumers.get(ctx.getSourceRequestIndex())
        );

        // Both requests scheduled at the SAME atTime so the wire-time gate, not the schedule,
        // is what holds B back. Both share the same source connection.
        var atTime = Instant.now();
        var aCtx = rootContext.getTestConnectionRequestContext(0);
        var bCtx = rootContext.getTestConnectionRequestContext(1);

        // Pre-flip the multiplexed flag so the orchestrator takes the wire-time-aware branch
        // for the very first request. In production the flag flips on first consumer creation,
        // but the test must be deterministic about which branch each request takes.
        clientConnectionPool.getCachedSession(aCtx.getLogicalEnclosingScope(),
            aCtx.getReplayerRequestKey().sourceRequestIndexSessionIdentifier).setMultiplexed(true);

        var aFuture = orchestrator.scheduleRequest(
            aCtx.getReplayerRequestKey(), aCtx, atTime, Duration.ZERO,
            ByteBufListProducer.of(new ByteBufList(Unpooled.wrappedBuffer(new byte[]{0}))),
            new NoRetryEvaluatorFactory.NoRetryVisitor(),
            aWireTimes
        );
        var bFuture = orchestrator.scheduleRequest(
            bCtx.getReplayerRequestKey(), bCtx, atTime, Duration.ZERO,
            ByteBufListProducer.of(new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1}))),
            new NoRetryEvaluatorFactory.NoRetryVisitor(),
            bWireTimes
        );

        var aConsumer = consumers.get(0L);
        var bConsumer = consumers.get(1L);

        // A's consumeBytes must fire (it's the first request, gate is completed).
        long aConsumeBytesAt = aConsumer.consumeBytesFiredAtNanos.get(10, TimeUnit.SECONDS);

        // Sleep briefly so any racing dispatch surfaces. If the wiring is broken, B fires.
        Thread.sleep(RACE_QUIESCE.toMillis());
        Assertions.assertFalse(bConsumer.consumeBytesFiredAtNanos.isDone(),
            "B's consumeBytes fired before A's response landed: gate not honoring wire-time dependency");

        // Release A's send-side. Response is still gated.
        aConsumer.consumeBytesGate.release();
        long aFinalizeFiredAt = aConsumer.finalizeFiredAtNanos.get(10, TimeUnit.SECONDS);
        Thread.sleep(RACE_QUIESCE.toMillis());
        Assertions.assertFalse(bConsumer.consumeBytesFiredAtNanos.isDone(),
            "B's consumeBytes fired after A's send but before A's response finalized");

        aConsumer.finalizeGate.release();
        // Now A is done end-to-end. B's gate should fire.
        long bConsumeBytesAt = bConsumer.consumeBytesFiredAtNanos.get(10, TimeUnit.SECONDS);
        Assertions.assertTrue(bConsumeBytesAt > aFinalizeFiredAt,
            "B's consumeBytes must fire after A's finalizeRequest");

        // Drain B normally so the orchestrator futures resolve.
        bConsumer.consumeBytesGate.release();
        bConsumer.finalizeGate.release();

        var aResp = aFuture.get();
        var bResp = bFuture.get();
        Assertions.assertNull(aResp.getError());
        Assertions.assertNull(bResp.getError());

        log.atInfo().setMessage("Wire-time gate honored: A.consume@{}ns A.finalize@{}ns B.consume@{}ns")
            .addArgument(aConsumeBytesAt).addArgument(aFinalizeFiredAt).addArgument(bConsumeBytesAt).log();
    }

    @Test
    @Timeout(30)
    void multiplexedConcurrentSuccessor_dispatchesImmediately() throws Exception {
        // Source-side wire-time anchors: B's request overlaps A's request (truly concurrent).
        var T0 = Instant.parse("2025-01-01T00:00:00Z");
        var aWireTimes = new WireTimeAnchors(
            T0,
            T0.plusMillis(20),
            T0.plusMillis(20),
            T0.plusMillis(40));
        var bWireTimes = new WireTimeAnchors(
            T0.plusMillis(5),            // B.requestFirstFrame BEFORE A.requestLastFrame
            T0.plusMillis(15),
            T0.plusMillis(15),
            T0.plusMillis(35));

        var clientConnectionPool = TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
            new URI("http://localhost"),
            false,
            1,
            "WireTimeOrchestratorWiringTest concurrent pool"
        );

        var consumers = new HashMap<Long, BlockingPacketConsumer>();
        consumers.put(0L, new BlockingPacketConsumer(0));
        consumers.put(1L, new BlockingPacketConsumer(1));

        var orchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (replaySession, ctx) -> consumers.get(ctx.getSourceRequestIndex())
        );

        var atTime = Instant.now();
        var aCtx = rootContext.getTestConnectionRequestContext(0);
        var bCtx = rootContext.getTestConnectionRequestContext(1);

        // Pre-flip multiplexed flag so the mux branch is taken on the first request.
        clientConnectionPool.getCachedSession(aCtx.getLogicalEnclosingScope(),
            aCtx.getReplayerRequestKey().sourceRequestIndexSessionIdentifier).setMultiplexed(true);

        var aFuture = orchestrator.scheduleRequest(
            aCtx.getReplayerRequestKey(), aCtx, atTime, Duration.ZERO,
            ByteBufListProducer.of(new ByteBufList(Unpooled.wrappedBuffer(new byte[]{0}))),
            new NoRetryEvaluatorFactory.NoRetryVisitor(),
            aWireTimes
        );
        var bFuture = orchestrator.scheduleRequest(
            bCtx.getReplayerRequestKey(), bCtx, atTime, Duration.ZERO,
            ByteBufListProducer.of(new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1}))),
            new NoRetryEvaluatorFactory.NoRetryVisitor(),
            bWireTimes
        );

        // Both consumers must fire consumeBytes without either response landing first,
        // proving B's dispatch was NOT gated on A's response (classifier returned CONCURRENT).
        consumers.get(0L).consumeBytesFiredAtNanos.get(10, TimeUnit.SECONDS);
        consumers.get(1L).consumeBytesFiredAtNanos.get(10, TimeUnit.SECONDS);

        // Drain.
        for (var c : consumers.values()) {
            c.consumeBytesGate.release();
            c.finalizeGate.release();
        }
        aFuture.get();
        bFuture.get();
    }
}
