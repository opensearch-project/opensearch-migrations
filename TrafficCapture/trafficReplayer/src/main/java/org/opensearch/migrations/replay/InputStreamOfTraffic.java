package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class InputStreamOfTraffic implements ITrafficCaptureSource {
    private final InputStream inputStream;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();

    public InputStreamOfTraffic(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    /**
     * Returns a CompletableFuture to a TrafficStream object or sets the cause exception to an
     * EOFException if the input has been exhausted.
     *
     * @return
     */
    public CompletableFuture<List<TrafficStream>> readNextTrafficStreamChunk() {
        return CompletableFuture.supplyAsync(() -> {
            var builder = TrafficStream.newBuilder();
            try {
                if (!builder.mergeDelimitedFrom(inputStream)) {
                    throw new EOFException();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            var ts = builder.build();
            log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.incrementAndGet(), ts);
            return List.of(ts);
        }).exceptionally(e->{
            var ecf = new CompletableFuture<List<TrafficStream>>();
            ecf.completeExceptionally(e.getCause().getCause());
            return ecf.join();
        });
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
