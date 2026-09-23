package org.opensearch.migrations.replay.e2etests;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: ISimpleTrafficCaptureSource ITrafficSourceContexts ITrafficStreamKey SourcePartitionLifecycleListener . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.io.EOFException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.SourcePartitionLifecycleListener;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class SentinelSensingTrafficSource implements ISimpleTrafficCaptureSource {
    public static final String SENTINEL_CONNECTION_ID = "EOF_MARKER_TRAFFIC_STREAM";
    private final ISimpleTrafficCaptureSource underlyingSource;
    private final AtomicBoolean stopReadingRef;

    public SentinelSensingTrafficSource(ISimpleTrafficCaptureSource underlyingSource) {
        this.underlyingSource = underlyingSource;
        stopReadingRef = new AtomicBoolean();
    }

    @Override
    public CompletableFuture<List<org.opensearch.migrations.replay.traffic.source.SourceInput>>
    readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        if (stopReadingRef.get()) {
            return CompletableFuture.failedFuture(new EOFException());
        }
        return underlyingSource.readNextTrafficStreamChunk(contextSupplier).thenApply(v -> {
            if (v != null) {
                return v.stream().takeWhile(input -> {
                    if (!(input instanceof ITrafficStreamWithKey ts)) {
                        return true;
                    }
                    var isSentinel = ts.getStream().getConnectionId().equals(SENTINEL_CONNECTION_ID);
                    if (isSentinel) {
                        stopReadingRef.set(true);
                    }
                    return !isSentinel;
                }).collect(Collectors.toList());
            } else {
                return v;
            }
        });
    }

    @Override
    public CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey) {
        return underlyingSource.acknowledgeSessionTermination(sessionKey);
    }

    @Override
    public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
        underlyingSource.onConnectionAccumulationComplete(trafficStreamKey);
    }

    @Override
    public CompletionStage<Void> recordProcessingFinished(KafkaRecordId recordId) {
        return underlyingSource.recordProcessingFinished(recordId);
    }

    @Override
    public RecordId recordIdFor(ITrafficStreamKey trafficStreamKey) {
        return underlyingSource.recordIdFor(trafficStreamKey);
    }

    @Override
    public SourcePartitionKey sourcePartitionFor(ITrafficStreamKey trafficStreamKey) {
        return underlyingSource.sourcePartitionFor(trafficStreamKey);
    }

    @Override
    public void setSourcePartitionLifecycleListener(SourcePartitionLifecycleListener listener) {
        underlyingSource.setSourcePartitionLifecycleListener(listener);
    }

    @Override
    public boolean usesStructuralExpiration() {
        return underlyingSource.usesStructuralExpiration();
    }

    @Override
    public boolean hasPendingSourceControl() {
        return underlyingSource.hasPendingSourceControl();
    }

    @Override
    public boolean isReadCapacityAvailable() {
        return underlyingSource.isReadCapacityAvailable();
    }

    @Override
    public void setReadCapacityAvailableListener(Runnable listener) {
        underlyingSource.setReadCapacityAvailableListener(listener);
    }

    @Override
    public void touch(ITrafficSourceContexts.IBackPressureBlockContext context) {
        underlyingSource.touch(context);
    }

    @Override
    public Optional<Instant> getNextRequiredTouch() {
        return underlyingSource.getNextRequiredTouch();
    }

    @Override
    public void logHeartbeat() {
        underlyingSource.logHeartbeat();
    }

    @Override
    public void close() throws Exception {
        underlyingSource.close();
    }
}

*/
// REBUILD-LIMBO-END(G10)