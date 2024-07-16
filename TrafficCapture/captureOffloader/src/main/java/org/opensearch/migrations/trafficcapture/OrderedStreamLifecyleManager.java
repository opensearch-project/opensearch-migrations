package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;

public abstract class OrderedStreamLifecyleManager<T> implements StreamLifecycleManager<T> {
    CompletableFuture<T> futureForLastClose = CompletableFuture.completedFuture(null);

    public CompletableFuture<T> closeStream(CodedOutputStreamHolder outputStreamHolder, int index) {
        futureForLastClose = futureForLastClose.thenCompose(v -> kickoffCloseStream(outputStreamHolder, index));
        return futureForLastClose;
    }

    protected abstract CompletableFuture<T> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index);
}
