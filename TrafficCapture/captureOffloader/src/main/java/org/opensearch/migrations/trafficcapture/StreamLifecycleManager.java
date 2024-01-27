package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface StreamLifecycleManager<T> {
    CodedOutputStreamHolder createStream();

    CompletableFuture<T> closeStream(CodedOutputStreamHolder outputStreamHolder, int index);
}
