package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class CloseableTrafficStreamWrapper implements Closeable {
    private final Closeable underlyingCloseableResource;
    private final Stream<TrafficStream> underlyingStream;

    public static CloseableTrafficStreamWrapper generateTrafficStreamFromInputStream(InputStream is) {
        AtomicInteger trafficStreamsRead = new AtomicInteger();
        return new CloseableTrafficStreamWrapper(is, Stream.generate((Supplier) () -> {
            try {
                var builder = TrafficStream.newBuilder();
                if (!builder.mergeDelimitedFrom(is)) {
                    return null;
                }
                var ts = builder.build();
                log.trace("Parsed traffic stream #" + (trafficStreamsRead.incrementAndGet()) + ": "+ts);
                return ts;
            } catch (IOException e) {
                log.error("Got exception while reading input: "+e);
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

    public static CloseableTrafficStreamWrapper generateTrafficStreamFromMessageSource(ITrafficCaptureSource captureSource) {
        Stream<TrafficStream> stream = captureSource.consumeTrafficFromSource();
        return new CloseableTrafficStreamWrapper(captureSource, stream);
    }

    public static CloseableTrafficStreamWrapper getLogEntries(String filename, ITrafficCaptureSource captureSource) throws IOException {
        if (filename != null && captureSource != null) {
            throw new RuntimeException("Only one traffic source can be specified, detected options for input file as well as Kafka");
        }
        if (captureSource != null) {
            return generateTrafficStreamFromMessageSource(captureSource);
        }
        else if (filename != null) {
            return getLogEntriesFromFile(filename);
        }
        else {
            return getCaptureEntriesFromInputStream(System.in);
        }
    }

    private CloseableTrafficStreamWrapper(Closeable underlyingCloseableResource, Stream<TrafficStream> underlyingStream) {
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
