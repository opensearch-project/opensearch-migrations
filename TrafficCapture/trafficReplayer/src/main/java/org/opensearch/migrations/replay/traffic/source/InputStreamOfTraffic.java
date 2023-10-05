package org.opensearch.migrations.replay.traffic.source;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class InputStreamOfTraffic implements ISimpleTrafficCaptureSource {
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
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
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
            trafficStreamsRead.incrementAndGet();
            log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.get(), ts);
            return List.<ITrafficStreamWithKey>of(new TrafficStreamWithEmbeddedKey(ts));
        }).exceptionally(e->{
            var ecf = new CompletableFuture<List<ITrafficStreamWithKey>>();
            ecf.completeExceptionally(e.getCause().getCause());
            return ecf.join();
        });
    }

    @Override
    public void commitTrafficStream(TrafficStreamKey trafficStreamKey) {
        // do nothing - this datasource isn't transactional
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
