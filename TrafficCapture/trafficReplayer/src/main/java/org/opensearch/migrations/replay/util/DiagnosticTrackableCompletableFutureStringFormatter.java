package org.opensearch.migrations.replay.util;

import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DiagnosticTrackableCompletableFutureStringFormatter {

    private DiagnosticTrackableCompletableFutureStringFormatter() {}

    public static <D> String format(DiagnosticTrackableCompletableFuture<D,?> f) {
        return format(f, x->null);
    }

    public static <D> String format(DiagnosticTrackableCompletableFuture<D,?> f,
                                    @NonNull Function<DiagnosticTrackableCompletableFuture<D,?>,String> resultFormatter) {
        return f.walkParentsAsStream().map(kvp-> stringFormatFutureWithDiagnostics(f, kvp, resultFormatter))
                .collect(Collectors.joining("<-"));
    }

    @SneakyThrows
    protected static <D> String stringFormatFutureWithDiagnostics(
            DiagnosticTrackableCompletableFuture<D,?> f,
            @NonNull DiagnosticTrackableCompletableFuture<D,?> dcf,
            @NonNull Function<DiagnosticTrackableCompletableFuture<D,?>,String> resultFormatter) {
        var diagnosticInfo = dcf.diagnosticSupplier.get();
        var isDone = dcf.isDone();
        return "[" + System.identityHashCode(dcf) + "] " + diagnosticInfo +
                (isDone ?
                        "[" + Optional.ofNullable(resultFormatter.apply(dcf)).orElse("^") + "]" :
                        Optional.ofNullable(dcf.innerComposedPendingCompletableFutureReference)
                                .map(r -> (DiagnosticTrackableCompletableFuture<D, ?>) r.get())
                                .map(df -> " --[[" + format(df, resultFormatter) + " ]] ")
                                .orElse("[…]"));
    }
}
