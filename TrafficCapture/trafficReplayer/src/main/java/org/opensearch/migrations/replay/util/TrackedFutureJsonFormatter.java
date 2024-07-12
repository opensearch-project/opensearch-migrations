package org.opensearch.migrations.replay.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;

public class TrackedFutureJsonFormatter {

    static ObjectMapper objectMapper = new ObjectMapper();

    private TrackedFutureJsonFormatter() {}

    public static <D> String format(TrackedFuture<D, ?> tf) {
        return format(tf, x -> null);
    }

    public static <D> String format(
        TrackedFuture<D, ?> tf,
        @NonNull Function<TrackedFuture<D, ?>, String> resultFormatter
    ) {
        try {
            return objectMapper.writeValueAsString(makeJson(tf, resultFormatter));
        } catch (Exception e) {
            throw Lombok.sneakyThrow(e);
        }
    }

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
