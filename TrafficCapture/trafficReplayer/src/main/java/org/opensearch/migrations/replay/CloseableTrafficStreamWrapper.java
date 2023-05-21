package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class CloseableTrafficStreamWrapper implements Closeable {
    private final Closeable underlyingCloseableResource;
    private final Stream<TrafficStream> underlyingStream;

    public static CloseableTrafficStreamWrapper generateTrafficStreamFromInputStream(InputStream is) {
        return new CloseableTrafficStreamWrapper(is, Stream.generate((Supplier) () -> {
            try {
                var builder = TrafficStream.newBuilder();
                if (!builder.mergeDelimitedFrom(is)) {
                    return null;
                }
                var ts = builder.build();
                log.warn("Parsed traffic stream: "+ts);
                return ts;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).takeWhile(s -> s != null));
    }

    public static CloseableTrafficStreamWrapper getCaptureEntriesFromInputStream(InputStream is) throws IOException {
        return generateTrafficStreamFromInputStream(is);
    }

    public static CloseableTrafficStreamWrapper getLogEntriesFromFile(String filename) throws IOException {
        FileInputStream fis = new FileInputStream(filename);
        try {
            return getCaptureEntriesFromInputStream(fis);
        } catch (Exception e) {
            fis.close();
            throw e;
        }
    }

    public static CloseableTrafficStreamWrapper getLogEntriesFromFileOrStdin(String filename) throws IOException {
        return filename == null ? getCaptureEntriesFromInputStream(System.in) :
                getLogEntriesFromFile(filename);
    }


    public CloseableTrafficStreamWrapper(Closeable underlyingCloseableResource, Stream<TrafficStream> underlyingStream) {
        this.underlyingCloseableResource = underlyingCloseableResource;
        this.underlyingStream = underlyingStream;
    }

    @Override
    public void close() throws IOException {
        underlyingCloseableResource.close();
    }

    public Stream<TrafficStream> stream() {
        return underlyingStream;
    }
}
