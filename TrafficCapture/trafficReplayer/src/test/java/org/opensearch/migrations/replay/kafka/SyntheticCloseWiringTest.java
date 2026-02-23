package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashMap;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;

/**
 * Phase A failing tests for synthetic close wiring.
 */
public class SyntheticCloseWiringTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    /**
     * When a SyntheticPartitionReassignmentClose fires for a connection in ACCUMULATING_WRITES
     * state, fireAccumulationsCallbacksAndClose must be called BEFORE onConnectionClose, so that
     * finishedAccumulatingResponseFuture is completed and the OnlineRadixSorter can drain.
     *
     * Before fix: accumulator bypasses state machine for synthetic closes — no
     * fireAccumulationsCallbacksAndClose is called, so onTrafficStreamsExpired/handleEndOfResponse
     * is never invoked for the in-flight request.
     */
    @Test
    void syntheticClose_completesFinishedAccumulatingResponseFuture() {
        var responseAccumulatedCallbackFired = new AtomicBoolean(false);
        var connectionCloseFired = new AtomicBoolean(false);

        // Track order: response callback must fire BEFORE onConnectionClose
        var orderTracker = new ArrayList<String>();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isHandoffConnection
                ) {
                    // Return the "finishedAccumulatingResponseFuture" completion callback
                    return pair -> {
                        responseAccumulatedCallbackFired.set(true);
                        orderTracker.add("responseCallback");
                    };
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    orderTracker.add("expired");
                    for (var tsk : trafficStreamKeysBeingHeld) {
                        tsk.getTrafficStreamsContext().close();
                    }
                }

                @Override
                public void onConnectionClose(
                    int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    connectionCloseFired.set(true);
                    orderTracker.add("connectionClose:" + status);
                    for (var tsk : trafficStreamKeysBeingHeld) {
                        tsk.getTrafficStreamsContext().close();
                    }
                }

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            }
        );

        var ts = Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build();

        // Feed a READ + EOM to get into ACCUMULATING_WRITES state (request dispatched)
        var readStream = TrafficStream.newBuilder()
            .setNodeId("node1").setConnectionId("conn1").setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: x\r\n\r\n", StandardCharsets.UTF_8))
                    .build()).build())
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setEndOfMessageIndicator(
                    EndOfMessageIndication.newBuilder()
                        .setFirstLineByteLength(16).setHeadersByteLength(12).build())
                .build())
            .build();

        var key1 = makeKafkaKey("node1", "conn1", 1, 0, 0);
        accumulator.accept(new PojoTrafficStreamAndKey(readStream, key1));

        // Now feed a synthetic close — should call fireAccumulationsCallbacksAndClose
        // which calls handleEndOfResponse → fullDataContinuation.accept(rrPair)
        // i.e., the responseCallback fires BEFORE onConnectionClose
        var syntheticKey = makeKafkaKey("node1", "conn1", 1, 0, 1);
        accumulator.accept(new SyntheticPartitionReassignmentClose(syntheticKey));

        Assertions.assertTrue(connectionCloseFired.get(),
            "onConnectionClose(REASSIGNED) must fire for synthetic close");
        Assertions.assertEquals(RequestResponsePacketPair.ReconstructionStatus.REASSIGNED,
            // check via orderTracker
            orderTracker.stream().filter(s -> s.startsWith("connectionClose:"))
                .map(s -> s.substring("connectionClose:".length()))
                .map(RequestResponsePacketPair.ReconstructionStatus::valueOf)
                .findFirst().orElse(null),
            "status must be REASSIGNED");

        // The response callback (finishedAccumulatingResponseFuture completion) must fire
        // before onConnectionClose so the sorter can drain
        Assertions.assertTrue(responseAccumulatedCallbackFired.get(),
            "fireAccumulationsCallbacksAndClose must complete finishedAccumulatingResponseFuture " +
            "before onConnectionClose fires");
        int responseIdx = orderTracker.indexOf("responseCallback");
        int closeIdx = orderTracker.stream()
            .map(s -> orderTracker.indexOf(s))
            .filter(i -> orderTracker.get(i).startsWith("connectionClose:"))
            .findFirst().orElse(-1);
        Assertions.assertTrue(responseIdx < closeIdx,
            "response callback must fire before onConnectionClose, but order was: " + orderTracker);
    }

    /**
     * onConnectionClose(REASSIGNED) must call replayEngine.closeConnection() — currently skipped.
     * We verify this indirectly: the REASSIGNED path must NOT return early before scheduling
     * the close. We check that the close IS scheduled by verifying the session's sorter has work.
     *
     * This test is a placeholder — the full verification requires integration with ReplayEngine.
     * The key assertion: REASSIGNED status does NOT skip replayEngine.closeConnection().
     */
    @Test
    void syntheticClose_doesNotSkipReplayEngineClose() {
        var reassignedCloseCallCount = new AtomicInteger(0);
        var replayEngineCloseCalled = new AtomicBoolean(false);

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isHandoffConnection
                ) { return pair -> {}; }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    for (var tsk : trafficStreamKeysBeingHeld) tsk.getTrafficStreamsContext().close();
                }

                @Override
                public void onConnectionClose(
                    int channelInteractionNum,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int sessionNumber,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    if (status == RequestResponsePacketPair.ReconstructionStatus.REASSIGNED) {
                        reassignedCloseCallCount.incrementAndGet();
                        // After fix: channelInteractionNum and sessionNumber should be valid
                        // (not both 0 unless the accumulation was empty)
                        // For now just verify it's called
                    }
                    for (var tsk : trafficStreamKeysBeingHeld) tsk.getTrafficStreamsContext().close();
                }

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            }
        );

        var key = makeKafkaKey("node1", "conn2", 1, 0, 0);
        accumulator.accept(new SyntheticPartitionReassignmentClose(key));

        Assertions.assertEquals(1, reassignedCloseCallCount.get(),
            "onConnectionClose(REASSIGNED) must be called exactly once for synthetic close");
    }

    // -------------------------------------------------------------------------
    // Phase A: outstandingSyntheticCloseSessions counter tests
    // -------------------------------------------------------------------------

    /**
     * After draining syntheticCloseQueue, readNextTrafficStreamSynchronously must return
     * empty list while outstandingSyntheticCloseSessions > 0.
     * Before fix: counter doesn't exist, real records returned immediately.
     */
    @Test
    void emptyBatchReturnedWhileCounterPositive() throws Exception {
        var mc = new org.apache.kafka.clients.consumer.MockConsumer<String, byte[]>(
            org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST);
        var tp = new org.apache.kafka.common.TopicPartition("test", 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, "test", Duration.ofHours(1))) {
            // Simulate counter > 0
            source.outstandingSyntheticCloseSessions.set(1);

            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                // Add a real record
                var ts = TrafficStream.newBuilder()
                    .setNodeId("n").setConnectionId("c").setNumberOfThisLastChunk(0)
                    .addSubStream(TrafficObservation.newBuilder()
                        .setTs(Timestamp.newBuilder().setSeconds(1).build())
                        .setRead(ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n\r\n", StandardCharsets.UTF_8))
                            .build()).build())
                    .build();
                try (var baos = new ByteArrayOutputStream()) {
                    ts.writeTo(baos);
                    mc.addRecord(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        "test", 0, 0, "k", baos.toByteArray()));
                } catch (Exception e) { throw new RuntimeException(e); }
            });

            var result = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            Assertions.assertTrue(result.isEmpty(),
                "must return empty batch while outstandingSyntheticCloseSessions > 0, got: " + result.size());
        }
    }

    private TrafficStreamKeyWithKafkaRecordId makeKafkaKey(
        String nodeId, String connectionId, int generation, int partition, long offset
    ) {
        var ts = TrafficStream.newBuilder()
            .setNodeId(nodeId).setConnectionId(connectionId).setNumberOfThisLastChunk(0).build();
        return new TrafficStreamKeyWithKafkaRecordId(
            k -> new ReplayContexts.KafkaRecordContext(
                rootContext,
                new ChannelContextManager(rootContext).retainOrCreateContext(k),
                "", 1
            ),
            ts, generation, partition, offset
        );
    }
}
