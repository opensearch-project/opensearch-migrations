package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface StreamLifecycleManager<T> extends AutoCloseable {
    CodedOutputStreamHolder createStream();

    CompletableFuture<T> closeStream(CodedOutputStreamHolder outputStreamHolder, int index);

    void close() throws IOException;
}
