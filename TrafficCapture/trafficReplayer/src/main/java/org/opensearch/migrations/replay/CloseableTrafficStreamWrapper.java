package org.opensearch.migrations.replay;

import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CloseableTrafficStreamWrapper implements Closeable {
    private final Closeable underlyingCloseableResource;
    private final Stream<TrafficStream> underlyingStream;

    public CloseableTrafficStreamWrapper(Closeable underlyingCloseableResource, Stream<TrafficStream> underlyingStream) {
        this.underlyingCloseableResource = underlyingCloseableResource;
        this.underlyingStream = underlyingStream;
    }

    static CloseableTrafficStreamWrapper generateTrafficStreamFromInputStream(InputStream is) {
        return new CloseableTrafficStreamWrapper(is, Stream.generate((Supplier) () -> {
            try {
                var builder = TrafficStream.newBuilder();
                if (!builder.mergeDelimitedFrom(is)) {
                    return null;
                }
                var ts = builder.build();
                return ts;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).takeWhile(s -> s != null));
    }

    @Override
    public void close() throws IOException {
        underlyingCloseableResource.close();
    }

    public Stream<TrafficStream> stream() {
        return underlyingStream;
    }
}
