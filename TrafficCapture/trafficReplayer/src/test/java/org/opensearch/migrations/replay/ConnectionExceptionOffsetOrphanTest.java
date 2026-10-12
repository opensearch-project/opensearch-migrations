package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for offset-commit bugs in the accumulator:
 *
 * F1: A connectionException during ACCUMULATING_READS used to orphan offsets from prior
 *     TrafficStream records (their TSKs were held in a rrPair that resetForNextRequest nulled
 *     without committing).
 *
 * F2: Expiry of a keep-alive connection mid-second-request used to double-commit the same
 *     TSK list (onTrafficStreamsExpired + onConnectionClose in finally block), causing
 *     IllegalStateException.
 */
class ConnectionExceptionOffsetOrphanTest extends InstrumentationTest {

    private static final String NODE_ID = "testNode";
    private static final String CONN_ID = "conn1";

    private static Timestamp tsProto(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).build();
    }

    private static TrafficObservation readObs(long epochSeconds, int size) {
        return TrafficObservation.newBuilder()
            .setTs(tsProto(epochSeconds))
            .setRead(ReadObservation.newBuilder()
                .setData(ByteString.copyFrom(new byte[size])))
            .build();
    }

    private static TrafficObservation writeObs(long epochSeconds, int size) {
        return TrafficObservation.newBuilder()
            .setTs(tsProto(epochSeconds))
            .setWrite(WriteObservation.newBuilder()
                .setData(ByteString.copyFrom(new byte[size])))
            .build();
    }

    private static TrafficObservation eomObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(tsProto(epochSeconds))
            .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder())
            .build();
    }

    private static TrafficObservation connectionExceptionObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(tsProto(epochSeconds))
            .setConnectionException(ConnectionExceptionObservation.newBuilder()
                .setMessage("connection reset"))
            .build();
    }

    private static TrafficObservation closeObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(tsProto(epochSeconds))
            .setClose(CloseObservation.newBuilder())
            .build();
    }

    private static TrafficStream makeStream(String nodeId, String connId, int index,
                                            TrafficObservation... observations) {
        var builder = TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connId)
            .setNumber(index);
        for (var obs : observations) {
            builder.addSubStream(obs);
        }
        return builder.build();
    }

    private PojoTrafficStreamAndKey wrap(TrafficStream ts) {
        return new PojoTrafficStreamAndKey(ts,
            PojoTrafficStreamKeyAndContext.build(ts, rootContext::createTrafficStreamContextForTest));
    }

    /**
     * F1: Multi-record request + connectionException must commit ALL held TSKs.
     *
     * R1: READ (partial request, no EOM) — TSK held in rrPair
     * R2: READ (more of same request) — TSK held in rrPair
     * R3: connectionException — prior to fix, only R3 committed; R1+R2 orphaned forever
     *
     * After fix: all three TSKs should be reported as committed (via onTrafficStreamIgnored).
     */
    @Test
    void connectionExceptionCommitsAllHeldTsks() {
        var committedTsks = new ArrayList<ITrafficStreamKey>();
        var expiredTsks = new ArrayList<ITrafficStreamKey>();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    return rrPair -> {
                        rrPair.getTrafficStreamsHeld().forEach(committedTsks::add);
                    };
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    expiredTsks.addAll(trafficStreamKeysBeingHeld);
                }

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                                              int s, RequestResponsePacketPair.ReconstructionStatus status,
                                              @NonNull Instant when, @NonNull List<ITrafficStreamKey> keys) {
                    committedTsks.addAll(keys);
                }

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
                    committedTsks.add(ctx.getTrafficStreamKey());
                }
            });

        // R1: partial read (no EOM)
        var ts1 = makeStream(NODE_ID, CONN_ID, 0, readObs(100, 512));
        // R2: more of the same request (still no EOM)
        var ts2 = makeStream(NODE_ID, CONN_ID, 1, readObs(101, 512));
        // R3: connectionException terminates the connection mid-request
        var ts3 = makeStream(NODE_ID, CONN_ID, 2, connectionExceptionObs(102));

        accumulator.accept(wrap(ts1));
        accumulator.accept(wrap(ts2));
        accumulator.accept(wrap(ts3));

        // All three TSKs must be committed (via onTrafficStreamIgnored)
        Assertions.assertEquals(3, committedTsks.size(),
            "All three TSKs (R1, R2, R3) must be committed after connectionException; " +
            "prior to fix, only R3 was committed and R1+R2 were permanently orphaned");

        // Verify the connection IDs match
        committedTsks.forEach(tsk ->
            Assertions.assertEquals(CONN_ID, tsk.getConnectionId()));
    }

    /**
     * F2: Keep-alive connection expiry mid-second-request must NOT double-commit.
     *
     * Request 1: READ + EOM + WRITE (complete — numberOfResets becomes 1)
     * Request 2: READ (partial, no EOM)
     * Then: connection expires (timeout exceeded)
     *
     * Prior to fix: fireAccumulationsCallbacksAndClose called onTrafficStreamsExpired with
     * the TSK list, then the finally block called onConnectionClose with the SAME list →
     * double commit → IllegalStateException in OffsetLifecycleTracker.
     *
     * After fix: onTrafficStreamsExpired commits the list, then resetForNextRequest nulls
     * the rrPair so onConnectionClose sees an empty list.
     */
    @Test
    void keepAliveExpiryMidSecondRequestDoesNotDoubleCommit() {
        var expiredTsks = new ArrayList<ITrafficStreamKey>();
        var closedTsks = new ArrayList<ITrafficStreamKey>();
        var completedRequests = new AtomicInteger(0);

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(5), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    completedRequests.incrementAndGet();
                    return rrPair -> {};
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    expiredTsks.addAll(trafficStreamKeysBeingHeld);
                }

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                                              int s, RequestResponsePacketPair.ReconstructionStatus status,
                                              @NonNull Instant when, @NonNull List<ITrafficStreamKey> keys) {
                    // Only track closes for the connection under test
                    keys.stream()
                        .filter(k -> CONN_ID.equals(k.getConnectionId()))
                        .forEach(closedTsks::add);
                }

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            });

        // Request 1: full request-response cycle on connection (numberOfResets becomes 1)
        var ts1 = makeStream(NODE_ID, CONN_ID, 0,
            readObs(100, 64), eomObs(101), writeObs(102, 128));
        accumulator.accept(wrap(ts1));
        Assertions.assertEquals(1, completedRequests.get());

        // Request 2: partial read (no EOM) — second request starts
        var ts2 = makeStream(NODE_ID, CONN_ID, 1, readObs(103, 64));
        accumulator.accept(wrap(ts2));

        // Now trigger expiry by advancing time past the timeout (5s)
        // Feed a record on a DIFFERENT connection to advance the global clock
        var tsTrigger = makeStream(NODE_ID, "otherConn", 0, readObs(200, 1), closeObs(200));
        accumulator.accept(wrap(tsTrigger));

        // The original connection (CONN_ID) should have been expired without throwing.
        // Prior to fix: IllegalStateException from double removeAndReturnNewHead.
        // After fix: onTrafficStreamsExpired gets the held TSKs; onConnectionClose gets empty list.
        Assertions.assertFalse(expiredTsks.isEmpty(),
            "The partial second request's TSKs should have been expired via onTrafficStreamsExpired");
        Assertions.assertTrue(closedTsks.isEmpty(),
            "onConnectionClose for " + CONN_ID + " should receive an empty list " +
            "(rrPair nulled after expiry commit)");
    }
}
