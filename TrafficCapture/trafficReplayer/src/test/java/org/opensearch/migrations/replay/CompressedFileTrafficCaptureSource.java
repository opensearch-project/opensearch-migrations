package org.opensearch.migrations.replay;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;

public abstract class CompressedFileTrafficCaptureSource implements ISimpleTrafficCaptureSource {
    public static final int NUM_TRAFFIC_STREAMS_TO_READ = 1 * 1000;
    protected final ISimpleTrafficCaptureSource trafficSource;
    private final AtomicInteger numberOfTrafficStreamsToRead = new AtomicInteger(NUM_TRAFFIC_STREAMS_TO_READ);

    public CompressedFileTrafficCaptureSource(RootReplayerContext context, String filename) throws IOException {
        this.trafficSource = getTrafficSource(context, filename);
    }

    private static InputStreamOfTraffic getTrafficSource(RootReplayerContext context, String filename)
        throws IOException {
        var compressedIs = new FileInputStream(filename);
        var is = new GZIPInputStream(compressedIs);
        return new InputStreamOfTraffic(context, is);
    }

    @Override
    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        // do nothing
        return CommitResult.IMMEDIATE;
    }

    @Override
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> readChunkContextSupplier
    ) {
        if (numberOfTrafficStreamsToRead.get() <= 0) {
            return CompletableFuture.failedFuture(new EOFException());
        }
        return trafficSource.readNextTrafficStreamChunk(readChunkContextSupplier).thenApply(ltswk -> {
            var transformedTrafficStream = ltswk.stream().map(this::modifyTrafficStream).collect(Collectors.toList());
            var oldValue = numberOfTrafficStreamsToRead.get();
            var newValue = oldValue - transformedTrafficStream.size();
            var exchangeResult = numberOfTrafficStreamsToRead.compareAndExchange(oldValue, newValue);
            assert exchangeResult == oldValue : "didn't expect to be running with a race condition here";
            return transformedTrafficStream;
        });
    }

    protected abstract ITrafficStreamWithKey modifyTrafficStream(ITrafficStreamWithKey streamWithKey);

    @Override
    public void close() {
        // do nothing
    }

}
