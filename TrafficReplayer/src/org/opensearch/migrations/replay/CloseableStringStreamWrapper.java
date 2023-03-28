package org.opensearch.migrations.replay;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CloseableStringStreamWrapper implements Closeable {
    private final Closeable underlyingCloseableResource;
    private final Stream<String> underlyingStream;

    public CloseableStringStreamWrapper(Closeable underlyingCloseableResource, Stream<String> underlyingStream) {
        this.underlyingCloseableResource = underlyingCloseableResource;
        this.underlyingStream = underlyingStream;
    }

    static CloseableStringStreamWrapper generateStreamFromBufferedReader(BufferedReader br) {
        return new CloseableStringStreamWrapper(br, Stream.generate((Supplier) () -> {
            try {
                return br.readLine();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).takeWhile(s -> s != null));
    }

    @Override
    public void close() throws IOException {
        underlyingCloseableResource.close();
    }

    public Stream<String> stream() {
        return underlyingStream;
    }
}
