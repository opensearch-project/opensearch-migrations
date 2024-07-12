package org.opensearch.migrations.replay.util;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.NonNull;
import lombok.SneakyThrows;

public class TrackedFutureStringFormatter {

    private TrackedFutureStringFormatter() {}

    public static <D> String format(TrackedFuture<D, ?> f) {
        return format(f, x -> null);
    }

    public static <D> String format(
        TrackedFuture<D, ?> f,
        @NonNull Function<TrackedFuture<D, ?>, String> resultFormatter
    ) {
        return f.walkParentsAsStream()
            .map(kvp -> stringFormatFutureWithDiagnostics(f, kvp, resultFormatter))
            .collect(Collectors.joining("<-"));
    }

    @SneakyThrows
    protected static <D> String stringFormatFutureWithDiagnostics(
        TrackedFuture<D, ?> f,
        @NonNull TrackedFuture<D, ?> tf,
        @NonNull Function<TrackedFuture<D, ?>, String> resultFormatter
    ) {
        var diagnosticInfo = tf.diagnosticSupplier.get();
        var isDone = tf.isDone();
        return "["
            + System.identityHashCode(tf)
            + "] "
            + diagnosticInfo
            + (isDone
                ? "[" + Optional.ofNullable(resultFormatter.apply(tf)).orElse("^") + "]"
                : Optional.ofNullable(tf.innerComposedPendingCompletableFutureReference)
                    .map(r -> (TrackedFuture<D, ?>) r.get())
                    .map(df -> " --[[" + format(df, resultFormatter) + " ]] ")
                    .orElse("[…]"));
    }
}
