package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ActorRequestTestUtils {
    public static <T> TrackedFuture<String, T> schedulePreparedRequest(
        RequestSenderOrchestrator orchestrator,
        IReplayContexts.IReplayerHttpTransactionContext context,
        Instant start,
        Duration interval,
        ByteBufListProducer packetProducer,
        RequestSenderOrchestrator.RetryVisitor<T> visitor
    ) {
        return schedulePreparedRequest(
            orchestrator,
            context,
            start,
            interval,
            packetProducer,
            visitor,
            new AsyncPermitPool(1, Runnable::run)
        );
    }

    public static <T> TrackedFuture<String, T> schedulePreparedRequest(
        RequestSenderOrchestrator orchestrator,
        IReplayContexts.IReplayerHttpTransactionContext context,
        Instant start,
        Duration interval,
        ByteBufListProducer packetProducer,
        RequestSenderOrchestrator.RetryVisitor<T> visitor,
        AsyncPermitPool permitPool
    ) {
        var end = packetProducer.numByteBufs() > 1
            ? start.plus(interval.multipliedBy(packetProducer.numByteBufs() - 1L))
            : start;
        return orchestrator.scheduleRequestLifecycle(
            context.getReplayerRequestKey(),
            context,
            start.minus(ReplayEngine.EXPECTED_TRANSFORMATION_DURATION),
            start,
            end,
            permitPool,
            () -> TextTrackedFuture.completedFuture(
                new TransformedOutputAndResult<>(
                    packetProducer,
                    HttpRequestTransformationStatus.completed()
                ),
                () -> "prepared test request"
            ),
            transformed -> visitor,
            ignored -> null
        );
    }
}
