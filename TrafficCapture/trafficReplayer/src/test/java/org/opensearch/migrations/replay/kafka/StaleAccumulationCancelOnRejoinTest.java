package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * End-to-end regression test for the "Stale accumulation found" production failure that fired
 * when a single-partition single-consumer deployment was fenced and the same partition was
 * reassigned back to the consumer (the cooperative Revoked → Assigned path, never observed via
 * {@code onPartitionsLost}).
 *
 * <p>Bug shape (production log from 2026-05-20):
 * <pre>
 *   [WARN ] CommitFailedException: ... it is likely that the consumer was kicked out of the group
 *   [ERROR] Stale accumulation found for ... (stored gen=1, incoming gen=2)
 *           — TrafficSourceReaderInterruptedClose was not processed for this connection.
 * </pre>
 *
 * <p>Pre-fix mechanism:
 * <ol>
 *   <li>{@code onPartitionsRevoked([0])} added partition 0 to {@code pendingCleanupPartitions}.
 *   <li>{@code onPartitionsAssigned([0])} bumped the consumer generation, then the
 *       {@code trulyLost} filter excluded partition 0 (it came back) — <em>no synthetic close
 *       was enqueued.</em>
 *   <li>Kafka rewound the fetch position to the last committed offset, so post-commit records
 *       were re-delivered, stamped with the new generation.
 *   <li>The accumulator's defensive backstop fired.
 * </ol>
 *
 * <p>Post-fix:
 * <ul>
 *   <li>{@code TrackingKafkaConsumer.onPartitionsAssigned} treats every round-tripped partition
 *       as truly lost (the fetch position is reset regardless of whether it came back), and
 *       dispatches the truly-lost callback BEFORE the generation bump so synthetic close session
 *       keys carry the OLD generation that matches {@code session.generation}.
 *   <li>{@code KafkaTrafficCaptureSource.readNextTrafficStreamSynchronously} drains any
 *       synthetic closes enqueued during {@code poll()} and prepends them to the returned batch
 *       so they are processed before any new-generation records.
 *   <li>The accumulator's stale-accumulation branch becomes a defensive backstop only and must
 *       not fire under correct source-layer behavior.
 * </ul>
 */
public class StaleAccumulationCancelOnRejoinTest extends InstrumentationTest {

    private static final String TOPIC = "stale-accum-test";
    private static final String NODE_ID = "node1";
    private static final String CONN_ID = "conn-mid-flight";

    /**
     * End-to-end Path 2 reproduction:
     * <pre>
     *   poll #1 → assign partition 0 (gen=1), deliver READ+EOM → ACCUMULATING_WRITES
     *   poll #2 → revoke + reassign partition 0 (round-trip),
     *             also deliver re-delivered record for the same connection
     * </pre>
     *
     * After poll #2 the source layer must inject a synthetic close BEFORE the new-generation
     * record, the in-flight connection must close with TRAFFIC_SOURCE_READER_INTERRUPTED, and
     * the re-delivered record must create a fresh accumulation. The accumulator's defensive
     * stale-check branch must NOT fire.
     */
    @Test
    void revokeAndReassign_synthClosesBeforeNewGenRecord() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        // Capture every callback the accumulator emits so we can assert on the *exact* status
        // and ordering — there must be exactly one onConnectionClose for the mid-flight
        // connection, with status TRAFFIC_SOURCE_READER_INTERRUPTED.
        var connectionCloseStatuses =
            new ArrayList<RequestResponsePacketPair.ReconstructionStatus>();
        var connectionCloseConnIds = new ArrayList<String>();
        var requestsReceived = new AtomicInteger();
        var responsesCompleted = new AtomicInteger();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    requestsReceived.incrementAndGet();
                    return pair -> responsesCompleted.incrementAndGet();
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    for (var tsk : trafficStreamKeysBeingHeld) {
                        tsk.getTrafficStreamsContext().close();
                    }
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
                    connectionCloseStatuses.add(status);
                    connectionCloseConnIds.add(ctx.getConnectionId());
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

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // ---- poll #1: gen=1 — connection becomes mid-request (ACCUMULATING_WRITES) ----
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));    // → onPartitionsAssigned, gen=1
                addReadEomRecord(mc, 0L);
            });
            drainOnce(source, accumulator);

            Assertions.assertEquals(1, requestsReceived.get(),
                "READ+EOM record on poll #1 should produce exactly one request callback "
                    + "(connection now in ACCUMULATING_WRITES state)");
            Assertions.assertEquals(0, connectionCloseStatuses.size(),
                "no connection close should fire yet — connection is still mid-flight on gen=1");

            // ---- poll #2: revoke + reassign (Path 2) — generation bumps to 2 ----
            // The MockConsumer rebalance(empty) call invokes onPartitionsRevoked for currently
            // assigned partitions; the follow-up rebalance([tp]) invokes onPartitionsAssigned
            // for the same partition. Together this matches the cooperative Revoked → Assigned
            // sequence that single-partition single-consumer deployments hit on every fence/rejoin.
            //
            // Source-layer guarantees being exercised:
            //   1. onPartitionsAssigned dispatches the truly-lost callback BEFORE the generation
            //      bump, so synthetic-close session keys carry the OLD generation.
            //   2. The trulyLost filter no longer excludes round-trip partitions — partition 0
            //      IS treated as truly lost despite being reassigned back.
            //   3. The synthetic close is enqueued in trafficSourceReaderInterruptedCloseQueue
            //      while the same poll() also returns the re-delivered gen=2 record.
            //   4. readNextTrafficStreamSynchronously drains the queue after the poll and
            //      PREPENDS the synthetic close before the gen=2 record.
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.emptyList());          // onPartitionsRevoked → pendingCleanup={0}
                mc.rebalance(Collections.singletonList(tp));    // onPartitionsAssigned: dispatch lost(0), then gen=2
                addReadEomRecord(mc, 1L);                       // re-delivered record at offset 1
            });

            // Drain a single source.readNextTrafficStreamChunk() and capture the order of
            // ITrafficStreamWithKey types the accumulator received. The synthetic close must
            // appear before the real (re-delivered) record.
            var deliveryOrder = drainAndCaptureOrder(source, accumulator);

            // ---- Source-layer ordering ----
            int firstSyntheticIdx = -1;
            int firstRealIdx = -1;
            for (int i = 0; i < deliveryOrder.size(); i++) {
                var item = deliveryOrder.get(i);
                if (firstSyntheticIdx < 0 && item instanceof TrafficSourceReaderInterruptedClose) {
                    firstSyntheticIdx = i;
                }
                if (firstRealIdx < 0 && !(item instanceof TrafficSourceReaderInterruptedClose)) {
                    firstRealIdx = i;
                }
            }
            Assertions.assertTrue(firstSyntheticIdx >= 0,
                "source layer must inject a TrafficSourceReaderInterruptedClose for the round-tripped "
                    + "partition; nothing synthetic was delivered. Order=" + deliveryOrder);
            Assertions.assertTrue(firstRealIdx >= 0,
                "the re-delivered gen=2 record must still be returned (not dropped). Order=" + deliveryOrder);
            Assertions.assertTrue(firstSyntheticIdx < firstRealIdx,
                "synthetic close MUST be processed before the new-generation record so the in-flight "
                    + "accumulation is closed cleanly. syntheticIdx=" + firstSyntheticIdx
                    + " realIdx=" + firstRealIdx + " order=" + deliveryOrder);

            // ---- End-to-end close semantics ----
            Assertions.assertEquals(1, connectionCloseStatuses.size(),
                "exactly one onConnectionClose must fire for the mid-flight connection; got: "
                    + connectionCloseStatuses);
            Assertions.assertEquals(
                RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED,
                connectionCloseStatuses.get(0),
                "in-flight connection must close with TRAFFIC_SOURCE_READER_INTERRUPTED so "
                    + "replayEngine.cancelConnection runs and the channel session is marked cancelled "
                    + "(prevents the cached channel from self-healing onto the dead session)");
            Assertions.assertEquals(CONN_ID, connectionCloseConnIds.get(0),
                "the close must target the mid-flight connection on the round-tripped partition");

            // The in-flight request's response future must be completed exactly once so the
            // OnlineRadixSorter can drain.
            Assertions.assertEquals(1, responsesCompleted.get(),
                "fireAccumulationsCallbacksAndClose must complete the in-flight request's "
                    + "finishedAccumulatingResponseFuture exactly once");

            // The re-delivered record creates a fresh request — proves end-to-end recovery works.
            Assertions.assertEquals(2, requestsReceived.get(),
                "after the synthetic close clears the gen=1 accumulation, the re-delivered gen=2 "
                    + "record must create a fresh request");
        }
    }

    /**
     * Drains one chunk from the source, feeds each record into the accumulator (mirroring
     * {@code TrafficReplayerCore.pullCaptureFromSourceToAccumulator}), and returns the items in
     * the exact order they were delivered. Loops past empty batches caused by the
     * synthetic-close-pending guard until at least one item is delivered.
     */
    private List<ITrafficStreamWithKey> drainAndCaptureOrder(
        KafkaTrafficCaptureSource source,
        CapturedTrafficToHttpTransactionAccumulator accumulator
    ) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            var batch = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            if (!batch.isEmpty()) {
                for (ITrafficStreamWithKey ts : batch) {
                    accumulator.accept(ts);
                }
                return batch;
            }
        }
        throw new AssertionError("drainAndCaptureOrder: source returned no records after 16 polls");
    }

    /** Mimics one iteration of {@code TrafficReplayerCore.pullCaptureFromSourceToAccumulator}. */
    private void drainOnce(
        KafkaTrafficCaptureSource source,
        CapturedTrafficToHttpTransactionAccumulator accumulator
    ) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            var batch = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            if (!batch.isEmpty()) {
                for (ITrafficStreamWithKey ts : batch) {
                    accumulator.accept(ts);
                }
                return;
            }
        }
        throw new AssertionError("drainOnce: source returned no records after 16 polls");
    }

    /** Adds a READ + EOM record at the given offset for {@link #CONN_ID} on partition 0. */
    private static void addReadEomRecord(MockConsumer<String, byte[]> mc, long offset) {
        var ts = Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build();
        var stream = TrafficStream.newBuilder()
            .setNodeId(NODE_ID).setConnectionId(CONN_ID).setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: x\r\n\r\n", StandardCharsets.UTF_8))
                    .build())
                .build())
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setEndOfMessageIndicator(
                    EndOfMessageIndication.newBuilder()
                        .setFirstLineByteLength(16).setHeadersByteLength(12).build())
                .build())
            .build();
        try (var baos = new ByteArrayOutputStream()) {
            stream.writeTo(baos);
            mc.addRecord(new ConsumerRecord<>(TOPIC, 0, offset, "k", baos.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
