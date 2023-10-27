package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

public interface StreamLifecycleManager {
    CodedOutputStreamHolder createStream();

    CompletableFuture<Object> closeStream(CodedOutputStreamHolder outputStreamHolder, int index);
}
