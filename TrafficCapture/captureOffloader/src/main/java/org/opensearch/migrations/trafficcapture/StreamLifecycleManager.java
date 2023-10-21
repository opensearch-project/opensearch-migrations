package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

public abstract class StreamLifecycleManager {
    protected abstract CodedOutputStreamHolder createStream();

    protected abstract CompletableFuture<Object> closeStream(CodedOutputStreamHolder outputStreamHolder, int index);
}
