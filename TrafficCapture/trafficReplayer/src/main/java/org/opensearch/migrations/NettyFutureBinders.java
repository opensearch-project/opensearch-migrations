package org.opensearch.migrations;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.util.concurrent.Future;

public class NettyFutureBinders {

    private NettyFutureBinders() {}

    public static CompletableFuture<Void> bindNettyFutureToCompletableFuture(
        Future<?> nettyFuture,
        CompletableFuture<Void> cf
    ) {
        nettyFuture.addListener(f -> {
            if (!f.isSuccess()) {
                cf.completeExceptionally(f.cause());
            } else {
                cf.complete(null);
            }
        });
        return cf;
    }

    public static CompletableFuture<Void> bindNettyFutureToCompletableFuture(Future<?> nettyFuture) {
        return bindNettyFutureToCompletableFuture(nettyFuture, new CompletableFuture<>());
    }

    public static TrackedFuture<String, Void> bindNettyFutureToTrackableFuture(Future<?> nettyFuture, String label) {
        return new TextTrackedFuture<>(bindNettyFutureToCompletableFuture(nettyFuture), label);
    }

    public static TrackedFuture<String, Void> bindNettyFutureToTrackableFuture(
        Future<?> nettyFuture,
        Supplier<String> labelProvider
    ) {
        return new TextTrackedFuture<>(bindNettyFutureToCompletableFuture(nettyFuture), labelProvider);
    }

}
