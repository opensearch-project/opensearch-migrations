package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class InputStreamOfTraffic implements ITrafficCaptureSource {
    private final InputStream inputStream;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();

    public InputStreamOfTraffic(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public boolean readNextChunk(Consumer<TrafficStream> trafficStreamConsumer) {
        while (true) {
            try {
                var builder = TrafficStream.newBuilder();
                if (!builder.mergeDelimitedFrom(inputStream)) {
                    return false;
                }
                var ts = builder.build();
                log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.incrementAndGet(), ts);
                trafficStreamConsumer.accept(ts);
                return true;
            } catch (IOException e) {
                log.error("Got exception while reading input: "+e);
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
