package org.opensearch.migrations.trafficcapture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

public abstract class OrderedStreamLifecyleManager<T> implements StreamLifecycleManager<T> {
    CompletableFuture<T> futureForLastClose = CompletableFuture.completedFuture(null);

    public CompletableFuture<T> closeStream(CodedOutputStreamHolder outputStreamHolder, int index) {
        futureForLastClose = futureForLastClose.handle((ignored, priorFailure) -> {
            if (priorFailure != null) {
                outputStreamHolder.close();
                return CompletableFuture.<T>failedFuture(unwrapCompletionFailure(priorFailure));
            }
            try {
                return kickoffCloseStream(outputStreamHolder, index);
            } catch (Throwable kickoffFailure) {
                outputStreamHolder.close();
                return CompletableFuture.<T>failedFuture(kickoffFailure);
            }
        }).thenCompose(Function.identity());
        return futureForLastClose;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    }

    protected abstract CompletableFuture<T> kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index);
}
