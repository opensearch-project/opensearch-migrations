package org.opensearch.migrations.replay.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DiagnosticTrackableCompletableFutureJsonFormatter {

    static ObjectMapper objectMapper = new ObjectMapper();

    private DiagnosticTrackableCompletableFutureJsonFormatter() {}

    public static <D> String format(DiagnosticTrackableCompletableFuture<D,?> tf) {
        return format(tf, x->null);
    }

    public static <D> String format(DiagnosticTrackableCompletableFuture<D,?> tf,
                                     @NonNull Function<DiagnosticTrackableCompletableFuture<D,?>,String> resultFormatter) {
        try {
            return objectMapper.writeValueAsString(makeJson(tf, resultFormatter));
        } catch (Exception e) {
            throw Lombok.sneakyThrow(e);
        }
    }

    public static <D> List<Object> makeJson(DiagnosticTrackableCompletableFuture<D,?> tf,
                                            @NonNull Function<DiagnosticTrackableCompletableFuture<D,?>,String> resultFormatter) {
        return tf.walkParentsAsStream().map(kvp->jsonFormatFutureWithDiagnostics(kvp, resultFormatter))
                .collect(Collectors.toList());
    }

    @SneakyThrows
    protected static <D> Map<String, Object> jsonFormatFutureWithDiagnostics(
            @NonNull DiagnosticTrackableCompletableFuture<D,?> dcf,
            @NonNull Function<DiagnosticTrackableCompletableFuture<D,?>,String> resultFormatter) {
        var diagnosticInfo =  dcf.diagnosticSupplier.get();
        var isDone = dcf.isDone();
        var map = new LinkedHashMap<String, Object>();
        map.put("idHash", System.identityHashCode(dcf));
        map.put("label", diagnosticInfo);
        if (isDone) {
            map.put("value", Optional.ofNullable(resultFormatter.apply(dcf)).orElse("^"));
        } else {
            var innerResult = Optional.ofNullable(dcf.innerComposedPendingCompletableFutureReference)
                    .map(r -> (DiagnosticTrackableCompletableFuture<D, ?>) r.get())
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
