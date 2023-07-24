package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class TrafficCaptureInputStream implements ITrafficCaptureSource {
    private final InputStream inputStream;

    public TrafficCaptureInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<TrafficStream> supplyTrafficFromSource() {
        AtomicInteger trafficStreamsRead = new AtomicInteger();
        return Stream.generate((Supplier) () -> {
            try {
                var builder = TrafficStream.newBuilder();
                if (!builder.mergeDelimitedFrom(inputStream)) {
                    return null;
                }
                var ts = builder.build();
                log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.incrementAndGet(), ts);
                return ts;
            } catch (IOException e) {
                log.error("Got exception while reading input: "+e);
                throw new RuntimeException(e);
            }
        }).takeWhile(Objects::nonNull);
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
