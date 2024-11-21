package org.opensearch.migrations.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.NonNull;
import lombok.SneakyThrows;

public class TrackedFutureMapConverter {

    private TrackedFutureMapConverter() {}
    
    public static <D> List<Object> makeJson(
        TrackedFuture<D, ?> tf,
        @NonNull Function<TrackedFuture<D, ?>, String> resultFormatter
    ) {
        return tf.walkParentsAsStream()
            .map(kvp -> jsonFormatFutureWithDiagnostics(kvp, resultFormatter))
            .collect(Collectors.toList());
    }

    @SneakyThrows
    protected static <D> Map<String, Object> jsonFormatFutureWithDiagnostics(
        @NonNull TrackedFuture<D, ?> tf,
        @NonNull Function<TrackedFuture<D, ?>, String> resultFormatter
    ) {
        var diagnosticInfo = tf.diagnosticSupplier.get();
        var isDone = tf.isDone();
        var map = new LinkedHashMap<String, Object>();
        map.put("idHash", System.identityHashCode(tf));
        map.put("label", diagnosticInfo);
        if (isDone) {
            map.put("value", Optional.ofNullable(resultFormatter.apply(tf)).orElse("^"));
        } else {
            var innerResult = Optional.ofNullable(tf.innerComposedPendingCompletableFutureReference)
                .map(r -> (TrackedFuture<D, ?>) r.get())
                .map(df -> makeJson(df, resultFormatter))
                .orElse(null);
            if (innerResult == null) {
                map.put("value", "…");
            } else {
                map.put("pending", innerResult);
            }
        }
        return map;
    }
}
