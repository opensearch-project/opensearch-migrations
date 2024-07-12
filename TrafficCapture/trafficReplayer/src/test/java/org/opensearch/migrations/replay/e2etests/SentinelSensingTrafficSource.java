package org.opensearch.migrations.replay.e2etests;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
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
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        if (stopReadingRef.get()) {
            return CompletableFuture.failedFuture(new EOFException());
        }
        return underlyingSource.readNextTrafficStreamChunk(contextSupplier).thenApply(v -> {
            if (v != null) {
                return v.stream().takeWhile(ts -> {
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
    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {
        return underlyingSource.commitTrafficStream(trafficStreamKey);
    }

    @Override
    public void close() throws Exception {
        underlyingSource.close();
    }
}
