package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.scheduling.WireTimeAnchors;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

public interface AccumulationCallbacks {
    /**
     * @param isResumedConnection true when this is the first request on a connection that was
     *                            mid-flight during a Kafka partition reassignment.
     */
    Consumer<RequestResponsePacketPair> onRequestReceived(
        @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull HttpMessageAndTimestamp request,
        boolean isResumedConnection
    );

    /**
     * Wire-aware overload. The H2 path of {@link CapturedTrafficToHttpTransactionAccumulator}
     * calls this with non-null source-side wire-time anchors so the orchestrator can install a
     * per-session dispatch dependency before the request hits the wire. Default implementation
     * delegates to the timing-free overload — implementations that don't care about wire timing
     * (most tests, the H1 path) get the same behavior they always had.
     *
     * @param wireTimes  source-side wire-level timestamps for this request, never null. The
     *                   record may have null fields when only partial timing was captured.
     */
    default Consumer<RequestResponsePacketPair> onRequestReceived(
        @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull HttpMessageAndTimestamp request,
        boolean isResumedConnection,
        @NonNull WireTimeAnchors wireTimes
    ) {
        return onRequestReceived(ctx, request, isResumedConnection);
    }

    void onTrafficStreamsExpired(
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    );

    void onConnectionClose(
        int channelInteractionNum,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull Instant timestamp,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    );

    void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx);
}
