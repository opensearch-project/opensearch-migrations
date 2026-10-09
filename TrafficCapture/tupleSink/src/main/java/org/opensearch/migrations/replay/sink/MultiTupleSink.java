package org.opensearch.migrations.replay.sink;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;

/**
 * Fan-out sink that delivers each tuple to multiple downstream sinks.
 * The caller's future completes only when ALL downstream sinks confirm durability.
 */
@Slf4j
public class MultiTupleSink implements TupleSink {

    private final List<TupleSink> sinks;

    public MultiTupleSink(List<TupleSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        var subFutures = sinks.stream()
            .map(sink -> {
                var f = new CompletableFuture<Void>();
                try {
                    sink.accept(tupleMap, f);
                } catch (RuntimeException e) {
                    f.completeExceptionally(e);
                }
                return f;
            })
            .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(subFutures).whenComplete((v, e) -> {
            if (e != null) {
                future.completeExceptionally(e);
            } else {
                future.complete(null);
            }
        });
    }

    @Override
    public void flush() {
        for (var sink : sinks) {
            try {
                sink.flush();
            } catch (RuntimeException e) {
                log.atError().setCause(e).setMessage("Error flushing TupleSink").log();
            }
        }
    }

    @Override
    public void close() {
        for (var sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                log.atError().setCause(e).setMessage("Error closing TupleSink").log();
            }
        }
    }
}
