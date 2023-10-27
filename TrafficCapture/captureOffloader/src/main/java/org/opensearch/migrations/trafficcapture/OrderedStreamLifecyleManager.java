package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

public abstract class OrderedStreamLifecyleManager implements StreamLifecycleManager {
    CompletableFuture<Object> futureForLastClose = CompletableFuture.completedFuture(null);

    public abstract CodedOutputStreamHolder createStream();

    public CompletableFuture<Object> closeStream(CodedOutputStreamHolder outputStreamHolder, int index) {
        futureForLastClose = futureForLastClose.thenCompose(v -> kickoffCloseStream(outputStreamHolder, index));
        return futureForLastClose;
    }

    protected abstract CompletableFuture<Object> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder,
                                                                    int index);
}
