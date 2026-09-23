package org.opensearch.migrations.replay.traffic.source;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ITrafficSourceContexts ITrafficStreamKey SourceInput SourcePartitionLifecycleListener . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

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

    CompletableFuture<List<SourceInput>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    );

    default CompletionStage<Void> recordProcessingFinished(KafkaRecordId recordId) {
        return CompletableFuture.completedFuture(null);
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

    default boolean usesStructuralExpiration() {
        return false;
    }

    default boolean hasPendingSourceControl() {
        return false;
    }

    default boolean isReadCapacityAvailable() {
        return true;
    }

    default void setReadCapacityAvailableListener(Runnable listener) {}

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Called by the accumulator when a connection's lifecycle is complete — either because a
     * source close observation was processed, the accumulation expired, or a synthetic
     * reassignment close was injected. Fires on the main thread.
     * <p>
     * This is an accumulator-level event: it means no more source traffic will be processed
     * for this connection. It does NOT mean the target-side Netty channel is closed yet.
     * Use this to clean up per-connection tracking state (e.g., {@code partitionToActiveConnections}).
     */
// REBUILD-LIMBO-START(G2)
/*
    void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey);

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Acknowledges that the complete target-side session lifecycle has settled: queued and
     * active work, transaction disposition, channel close, and cache removal.
     */
// REBUILD-LIMBO-START(G2)
/*
    CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey);

    default void close() throws Exception {}

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Keep-alive call to be used by the BlockingTrafficSource to keep this connection alive if
     * this is required.
     */
// REBUILD-LIMBO-START(G2)
/*
    default void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {}

*/
// REBUILD-LIMBO-END(G2)
    /**
     * @return The time that the next call to touch() must be completed for this source to stay
     * active.  Empty indicates that touch() does not need to be called to keep the
     * source active.
     */
// REBUILD-LIMBO-START(G2)
/*
    default Optional<Instant> getNextRequiredTouch() {
        return Optional.empty();
    }

*/
// REBUILD-LIMBO-END(G2)
    /** Emit a periodic heartbeat log. Default no-op for non-Kafka sources. */
// REBUILD-LIMBO-START(G2)
/*
    default void logHeartbeat() {}
}

*/
// REBUILD-LIMBO-END(G2)