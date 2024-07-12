package org.opensearch.migrations.replay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.util.NettyUtils;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

import static org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest.REGULAR_RESPONSE_TIMEOUT;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
class RequestSenderOrchestratorTest extends InstrumentationTest {

    public static class BlockingPacketConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {

        private final long id;
        public final Semaphore consumeIsReady = new Semaphore(0, true);
        public final Semaphore lastCheckIsReady = new Semaphore(0, true);
        private final AtomicInteger calls = new AtomicInteger();

        BlockingPacketConsumer(long id) {
            this.id = id;
        }

        @Override
        public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
            var index = calls.getAndIncrement();
            return new TextTrackedFuture<>(CompletableFuture.supplyAsync(() -> {
                try {
                    lastCheckIsReady.release();
                    log.atDebug()
                        .setMessage(() -> "trying to acquire semaphore for packet #" + index + " and id=" + id)
                        .log();
                    consumeIsReady.acquire();
                    log.atDebug().setMessage(() -> "Acquired semaphore for packet #" + index + " and id=" + id).log();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
                return (Void) null;
            }), () -> "consumeBytes waiting on test-gate semaphore release");
        }

        @Override
        public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
            var index = calls.getAndIncrement();
            return new TextTrackedFuture<>(CompletableFuture.supplyAsync(() -> {
                try {
                    lastCheckIsReady.release();
                    log.atDebug().setMessage(() -> "trying to acquire semaphore for finalize and id=" + id).log();
                    consumeIsReady.acquire();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
                return new AggregatedRawResponse(0, Duration.ZERO, null, null);
            }), () -> "finalizeRequest waiting on test-gate semaphore release");
        }
    }

    @Test
    public void testFutureGraphBuildout() throws Exception {
        final int NUM_REQUESTS_TO_SCHEDULE = 2;
        final int NUM_REPEATS = 1;
        final int NUM_PACKETS = 3;

        var clientConnectionPool = TrafficReplayerTopLevel.makeClientConnectionPool(
            new URI("http://localhost"),
            false,
            1,
            "testFutureGraphBuildout targetConnectionPool"
        );
        var connectionToConsumerMap = new HashMap<Long, BlockingPacketConsumer>();
        var senderOrchestrator = new RequestSenderOrchestrator(
            clientConnectionPool,
            (s, c) -> connectionToConsumerMap.get(c.getSourceRequestIndex())
        );
        var baseTime = Instant.EPOCH;
        Instant lastEndTime = baseTime;
        var scheduledRequests = new ArrayList<TrackedFuture<String, AggregatedRawResponse>>();
        for (int i = 0; i < NUM_REQUESTS_TO_SCHEDULE; ++i) {
            connectionToConsumerMap.put((long) i, new BlockingPacketConsumer(i));
            var requestContext = rootContext.getTestConnectionRequestContext(i);
            // same as the test below...
            // half the time schedule at the same time as the last one, the other half, 10ms later than the previous
            var perPacketShift = Duration.ofMillis(10 * i / NUM_REPEATS);
            var startTimeForThisRequest = baseTime.plus(perPacketShift);
            var requestPackets = IntStream.range(0, NUM_PACKETS)
                .mapToObj(b -> Unpooled.wrappedBuffer(new byte[] { (byte) b }))  // TODO refCnt issue
                .collect(Collectors.toList());
            var arrCf = senderOrchestrator.scheduleRequest(
                requestContext.getReplayerRequestKey(),
                requestContext,
                startTimeForThisRequest,
                Duration.ofMillis(1),
                requestPackets.stream()
            );

            log.info("Scheduled item to run at " + startTimeForThisRequest);
            scheduledRequests.add(arrCf);
            lastEndTime = startTimeForThisRequest.plus(perPacketShift.multipliedBy(requestPackets.size()));
        }
        var connectionCtx = rootContext.getTestConnectionRequestContext(NUM_REQUESTS_TO_SCHEDULE);
        var closeFuture = senderOrchestrator.scheduleClose(
            connectionCtx.getLogicalEnclosingScope(),
            NUM_REQUESTS_TO_SCHEDULE,
            0,
            lastEndTime.plus(Duration.ofMillis(100))
        );

        Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledRequests.size());
        var reversedScheduledRequests = new ArrayList<>(scheduledRequests);
        Collections.reverse(reversedScheduledRequests);
        for (int i = 0; i < scheduledRequests.size(); ++i) {
            for (int j = 0; j < NUM_PACKETS + 1; ++j) {
                var pktConsumer = connectionToConsumerMap.get((long) i);

                pktConsumer.lastCheckIsReady.acquire();
                int finalI = i;
                int finalJ = j;
                log.atInfo()
                    .setMessage(
                        () -> "cf @ "
                            + finalI
                            + ","
                            + finalJ
                            + " =\n"
                            + reversedScheduledRequests.stream()
                                .map(sr -> getParentsDiagnosticString(sr, ""))
                                .collect(Collectors.joining("\n---\n"))
                    )
                    .log();
                pktConsumer.consumeIsReady.release();
            }
        }
        for (var cf : scheduledRequests) {
            var arr = cf.get();
            log.info("Finalized cf=" + getParentsDiagnosticString(cf, ""));
            Assertions.assertNull(arr.error);
        }
        closeFuture.get();
    }

    private String getParentsDiagnosticString(TrackedFuture<String, ?> cf, String indent) {
        return cf.walkParentsAsStream()
            .map(
                tf -> Optional.ofNullable(tf.getInnerComposedPendingCompletableFuture())
                    .map(idf -> indent + "<\n" + getParentsDiagnosticString(idf, indent + " ") + "\n" + indent + ">\n")
                    .orElse("")
                    + indent
                    + tf.diagnosticSupplier.get()
                    + " ["
                    + System.identityHashCode(tf)
                    + "]"
                    + ": "
                    + tf.isDone()
            )
            .collect(Collectors.joining(";\n"));
    }

    @Test
    @Tag("longTest")
    @Execution(ExecutionMode.SAME_THREAD)
    public void testThatSchedulingWorks() throws Exception {
        final int NUM_REQUESTS_TO_SCHEDULE = 20;
        final int NUM_REPEATS = 2;

        try (
            var httpServer = SimpleHttpServer.makeServer(
                false,
                r -> TestHttpServerContext.makeResponse(r, Duration.ofMillis(100))
            )
        ) {
            var testServerUri = httpServer.localhostEndpoint();
            var clientConnectionPool = TrafficReplayerTopLevel.makeClientConnectionPool(
                testServerUri,
                false,
                1,
                "targetConnectionPool for testThatSchedulingWorks"
            );
            var senderOrchestrator = new RequestSenderOrchestrator(
                clientConnectionPool,
                (replaySession, ctx) -> new NettyPacketToHttpConsumer(replaySession, ctx, REGULAR_RESPONSE_TIMEOUT)
            );
            var baseTime = Instant.now();
            Instant lastEndTime = baseTime;
            var scheduledItems = new ArrayList<TrackedFuture<String, AggregatedRawResponse>>();
            for (int i = 0; i < NUM_REQUESTS_TO_SCHEDULE; ++i) {
                var requestContext = rootContext.getTestConnectionRequestContext(i);
                // half the time schedule at the same time as the last one, the other half, 10ms later than the previous
                var perPacketShift = Duration.ofMillis(10 * i / NUM_REPEATS);
                var startTimeForThisRequest = baseTime.plus(perPacketShift);
                var requestPackets = makeRequest(i / NUM_REPEATS);
                var arr = senderOrchestrator.scheduleRequest(
                    requestContext.getReplayerRequestKey(),
                    requestContext,
                    startTimeForThisRequest,
                    Duration.ofMillis(1),
                    requestPackets.stream()
                );
                log.info("Scheduled item to run at " + startTimeForThisRequest);
                scheduledItems.add(arr);
                lastEndTime = startTimeForThisRequest.plus(perPacketShift.multipliedBy(requestPackets.size()));
            }
            var connectionCtx = rootContext.getTestConnectionRequestContext(NUM_REQUESTS_TO_SCHEDULE);
            var closeFuture = senderOrchestrator.scheduleClose(
                connectionCtx.getLogicalEnclosingScope(),
                NUM_REQUESTS_TO_SCHEDULE,
                0,
                lastEndTime.plus(Duration.ofMillis(100))
            );

            Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledItems.size());
            for (int i = 0; i < scheduledItems.size(); ++i) {
                var cf = scheduledItems.get(i);
                var arr = cf.get();
                Assertions.assertNull(arr.error);
                Assertions.assertTrue(arr.responseSizeInBytes > 0);
                var packetBytesArr = arr.responsePackets.stream()
                    .map(SimpleEntry::getValue)
                    .collect(Collectors.toList());
                try (
                    var bufStream = NettyUtils.createRefCntNeutralCloseableByteBufStream(packetBytesArr);
                    var messageHolder = RefSafeHolder.create(
                        HttpByteBufFormatter.parseHttpMessageFromBufs(
                            HttpByteBufFormatter.HttpMessageType.RESPONSE,
                            bufStream
                        )
                    )
                ) {
                    var message = messageHolder.get();
                    Assertions.assertNotNull(message);
                    var response = (FullHttpResponse) message;
                    Assertions.assertEquals(200, response.status().code());
                    var body = response.content();
                    Assertions.assertEquals(
                        TestHttpServerContext.SERVER_RESPONSE_BODY_PREFIX + TestHttpServerContext.getUriForIthRequest(
                            i / NUM_REPEATS
                        ),
                        body.toString(StandardCharsets.UTF_8)
                    );
                }
            }
            closeFuture.get();
        }
    }

    private List<ByteBuf> makeRequest(int i) {
        // uncomment/swap for a simpler test case to run
        return // List.of(Unpooled.wrappedBuffer(getRequestString(i).getBytes()));
        TestHttpServerContext.getRequestString(i)
            .chars()
            .mapToObj(c -> Unpooled.wrappedBuffer(new byte[] { (byte) c }))
            .collect(Collectors.toList());
    }
}
