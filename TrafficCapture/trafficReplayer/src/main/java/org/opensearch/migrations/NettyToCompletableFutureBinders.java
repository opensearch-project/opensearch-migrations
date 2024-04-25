package org.opensearch.migrations;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

public class NettyToCompletableFutureBinders {
    public static CompletableFuture<Void>
    bindNettyFutureToCompletableFuture(Future<?> nettyFuture, CompletableFuture<Void> cf) {
        nettyFuture.addListener(f -> {
            if (!f.isSuccess()) {
                cf.completeExceptionally(f.cause());
            } else {
                cf.complete(null);
            }
        });
        return cf;
    }

    public static CompletableFuture<Void>
    bindNettyFutureToCompletableFuture(Future<?> nettyFuture) {
        return bindNettyFutureToCompletableFuture(nettyFuture, new CompletableFuture<>());
    }

    public static DiagnosticTrackableCompletableFuture<String, Void>
    bindNettyFutureToTrackableFuture(Future<?> nettyFuture, String label) {
        return new StringTrackableCompletableFuture<>(bindNettyFutureToCompletableFuture(nettyFuture), label);
    }

    public static DiagnosticTrackableCompletableFuture<String, Void>
    bindNettyFutureToTrackableFuture(Future<?> nettyFuture, Supplier<String> labelProvider) {
        return new StringTrackableCompletableFuture<>(bindNettyFutureToCompletableFuture(nettyFuture), labelProvider);
    }

    public static DiagnosticTrackableCompletableFuture<String, Void>
    bindNettyFutureToTrackableFuture(Function<Runnable, Future<?>> nettyFutureGenerator, String label) {
        return bindNettyFutureToTrackableFuture(nettyFutureGenerator.apply(() -> {
        }), label);
    }

    public static DiagnosticTrackableCompletableFuture<String, Void>
    bindNettySubmitToTrackableFuture(EventLoop eventLoop) {
        return bindNettyFutureToTrackableFuture(eventLoop::submit, "waiting for event loop submission");
    }

    public static DiagnosticTrackableCompletableFuture<String, Void>
    bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Duration delay) {
        var delayMs = Math.max(0, delay.toMillis());
        return bindNettyFutureToTrackableFuture(eventLoop.schedule(() -> {}, delayMs, TimeUnit.MILLISECONDS),
                "scheduling to run next send at " + delay + " in " + delayMs +" ms (clipped)");
    }

    public static CompletableFuture<Void>
    bindNettyScheduleToCompletableFuture(EventLoop eventLoop, Duration delay, CompletableFuture<Void> cf) {
        var delayMs = Math.max(0, delay.toMillis());
        return bindNettyFutureToCompletableFuture(eventLoop.schedule(() -> {
        }, delayMs, TimeUnit.MILLISECONDS), cf);
    }
}
