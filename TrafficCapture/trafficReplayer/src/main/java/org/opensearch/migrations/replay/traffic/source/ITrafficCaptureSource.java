package org.opensearch.migrations.replay.traffic.source;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TrafficStreamRecordId;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;

public interface ITrafficCaptureSource extends AutoCloseable {

    enum CommitResult {
        IMMEDIATE,
        AFTER_NEXT_READ,
        BLOCKED_BY_OTHER_COMMITS,
        IGNORED
    }

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    );

    CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException;

    default CompletionStage<Void> commitTrafficStreamAsync(ITrafficStreamKey trafficStreamKey) {
        try {
            commitTrafficStream(trafficStreamKey);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    default RecordId recordIdFor(ITrafficStreamKey trafficStreamKey) {
        return new TrafficStreamRecordId(
            new SourceConnectionKey(trafficStreamKey.getNodeId(), trafficStreamKey.getConnectionId()),
            trafficStreamKey.getTrafficStreamIndex(),
            trafficStreamKey.getSourceGeneration()
        );
    }

    default SourcePartitionKey sourcePartitionFor(ITrafficStreamKey trafficStreamKey) {
        var recordId = recordIdFor(trafficStreamKey);
        if (recordId instanceof KafkaRecordId kafkaRecordId) {
            return new SourcePartitionKey(
                kafkaRecordId.topic(),
                kafkaRecordId.partition(),
                kafkaRecordId.sourceGeneration()
            );
        }
        return new SourcePartitionKey("non-kafka-source", 0, trafficStreamKey.getSourceGeneration());
    }

    default void setSourcePartitionLifecycleListener(SourcePartitionLifecycleListener listener) {}

    /**
     * Called by the accumulator when a connection's lifecycle is complete — either because a
     * source close observation was processed, the accumulation expired, or a synthetic
     * reassignment close was injected. Fires on the main thread.
     * <p>
     * This is an accumulator-level event: it means no more source traffic will be processed
     * for this connection. It does NOT mean the target-side Netty channel is closed yet.
     * Use this to clean up per-connection tracking state (e.g., {@code partitionToActiveConnections}).
     */
    void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey);

    /**
     * Acknowledges that the complete target-side session lifecycle has settled: queued and
     * active work, transaction disposition, channel close, and cache removal.
     */
    CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey);

    default void close() throws Exception {}

    /**
     * Keep-alive call to be used by the BlockingTrafficSource to keep this connection alive if
     * this is required.
     */
    default void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {}

    /**
     * @return The time that the next call to touch() must be completed for this source to stay
     * active.  Empty indicates that touch() does not need to be called to keep the
     * source active.
     */
    default Optional<Instant> getNextRequiredTouch() {
        return Optional.empty();
    }

    /** Emit a periodic heartbeat log. Default no-op for non-Kafka sources. */
    default void logHeartbeat() {}
}
