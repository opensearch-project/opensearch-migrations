package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

public abstract class OrderedStreamLifecyleManager extends StreamLifecycleManager {
    CompletableFuture<Object> futureForLastClose = CompletableFuture.completedFuture(null);

    protected abstract CodedOutputStreamHolder createStream();

    protected CompletableFuture<Object> closeStream(CodedOutputStreamHolder outputStreamHolder, int index) {
        futureForLastClose = futureForLastClose.thenCompose(v -> kickoffCloseStream(outputStreamHolder, index));
        return futureForLastClose;
    }

    protected abstract CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder,
                                                                    int index);
}
