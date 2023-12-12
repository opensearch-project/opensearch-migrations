package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public abstract class CompressedFileTrafficCaptureSource implements ISimpleTrafficCaptureSource {
    public static final int NUM_TRAFFIC_STREAMS_TO_READ = 1 * 1000;
    protected final ISimpleTrafficCaptureSource trafficSource;
    private final AtomicInteger numberOfTrafficStreamsToRead = new AtomicInteger(NUM_TRAFFIC_STREAMS_TO_READ);

    public CompressedFileTrafficCaptureSource(String filename) throws IOException {
        this.trafficSource = getTrafficSource(filename);
    }

    private static InputStreamOfTraffic getTrafficSource(String filename) throws IOException {
        var compressedIs = new FileInputStream(filename);
        var is = new GZIPInputStream(compressedIs);
        return new InputStreamOfTraffic(is);
    }

    @Override
    public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        // do nothing
    }

    @Override
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
        if (numberOfTrafficStreamsToRead.get() <= 0) {
            return CompletableFuture.failedFuture(new EOFException());
        }
        return trafficSource.readNextTrafficStreamChunk()
                .thenApply(ltswk -> {
                    var transformed = ltswk.stream().map(this::modifyTrafficStream).collect(Collectors.toList());
                    var oldValue = numberOfTrafficStreamsToRead.get();
                    var newValue = oldValue - transformed.size();
                    var exchangeResult = numberOfTrafficStreamsToRead.compareAndExchange(oldValue, newValue);
                    assert exchangeResult == oldValue : "didn't expect to be running with a race condition here";
                    return transformed;
                });
    }

    protected abstract ITrafficStreamWithKey modifyTrafficStream(ITrafficStreamWithKey streamWithKey);

    @Override
    public void close() {
        // do nothing
    }

}
