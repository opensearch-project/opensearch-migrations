package org.opensearch.migrations.replay.util;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
public class DiagnosticTrackableCompletableFuture<D, T> {

    public final CompletableFuture<T> future;
    protected AtomicReference<DiagnosticTrackableCompletableFuture<D,T>> innerComposedPendingCompletableFutureReference;
    private final
    RecursiveImmutableChain<AbstractMap.SimpleEntry<DiagnosticTrackableCompletableFuture,Supplier<D>>>
            diagnosticSupplierChain;

    /**
     * This factory class is here so that subclasses can write their own versions that return objects
     * of their own subclass.
     */
    public static class factory {
        public static <T, D> DiagnosticTrackableCompletableFuture<D, T>
        failedFuture(@NonNull Exception e, @NonNull Supplier<D> diagnosticSupplier) {
            return new DiagnosticTrackableCompletableFuture<>(CompletableFuture.failedFuture(e),
                    diagnosticSupplier, null);
        }

        public static <U, D> DiagnosticTrackableCompletableFuture<D, U>
        completedFuture(U v, @NonNull Supplier<D> diagnosticSupplier) {
            return new DiagnosticTrackableCompletableFuture<>(CompletableFuture.completedFuture(v), diagnosticSupplier,
                    null);
        }

        public static <D> DiagnosticTrackableCompletableFuture<D, Void>
        allOf(@NonNull DiagnosticTrackableCompletableFuture<D,Void>[] allRemainingWorkArray,
              @NonNull Supplier<D> diagnosticSupplier) {
            return new DiagnosticTrackableCompletableFuture<>(
                    CompletableFuture.allOf(Arrays.stream(allRemainingWorkArray)
                            .map(tcf->tcf.future).toArray(CompletableFuture[]::new)),
                    diagnosticSupplier, null);
        }
    }

    private DiagnosticTrackableCompletableFuture(
            @NonNull CompletableFuture<T> future,
            @NonNull RecursiveImmutableChain<AbstractMap.SimpleEntry<DiagnosticTrackableCompletableFuture,Supplier<D>>>
                    diagnosticSupplierChain) {
        this.future = future;
        this.diagnosticSupplierChain = diagnosticSupplierChain;
    }

    public DiagnosticTrackableCompletableFuture(@NonNull CompletableFuture<T> future,
                                                @NonNull Supplier<D> diagnosticSupplier) {
        this(future, diagnosticSupplier, null);
    }

    private DiagnosticTrackableCompletableFuture(@NonNull CompletableFuture<T> future,
                                                 @NonNull Supplier<D> diagnosticSupplier,
                                                 RecursiveImmutableChain<AbstractMap.SimpleEntry
                                                         <DiagnosticTrackableCompletableFuture, Supplier<D>>>
                                                         lastTrackableCompletableFuture) {
        this.future = future;
        this.diagnosticSupplierChain =
                new RecursiveImmutableChain(makeDiagnosticPair(this, diagnosticSupplier),
                        lastTrackableCompletableFuture);
    }

    private static <T,D> AbstractMap.SimpleEntry<DiagnosticTrackableCompletableFuture,Supplier<D>>
    makeDiagnosticPair(@NonNull DiagnosticTrackableCompletableFuture<D,T> future,
                       @NonNull Supplier<D> diagnosticSupplier) {
        return new AbstractMap.SimpleEntry(future, diagnosticSupplier);
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    map(@NonNull Function<CompletableFuture<T>, CompletableFuture<U>> fn,
        @NonNull Supplier<D> diagnosticSupplier) {
        var newCf = fn.apply(future);
        return new DiagnosticTrackableCompletableFuture<>(newCf, diagnosticSupplier, diagnosticSupplierChain);
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    thenCompose(@NonNull Function<? super T, ? extends DiagnosticTrackableCompletableFuture<D, U>> fn,
                @NonNull Supplier<D> diagnosticSupplier) {
        var innerComposedCompletableFutureReference = new AtomicReference<DiagnosticTrackableCompletableFuture<D,U>>();
        var newCf = this.future.thenCompose(v->{
            var innerFuture = fn.apply(v);
            innerComposedCompletableFutureReference.set(innerFuture);
            return innerFuture.future;
        });
        var wrappedDiagnosticFuture =
                new DiagnosticTrackableCompletableFuture<>(newCf, diagnosticSupplier, diagnosticSupplierChain);
        wrappedDiagnosticFuture.innerComposedPendingCompletableFutureReference = innerComposedCompletableFutureReference;
        wrappedDiagnosticFuture.future.whenComplete((v2,t2)->innerComposedCompletableFutureReference.set(null));
        return wrappedDiagnosticFuture;
    }

    /**
     * Run handle(), which can take care of either value completions or Exceptions by sending
     * those through the fn argument.  This returns the DiagnosticTrackableCompletableFuture
     * that the application of fn will yield.
     *
     * NB/TODO - I can't yet figure out how to enforce type-checking on the incoming fn parameter.
     * For example, I can pass a fn as a lambda which returns a String or an integer and the
     * compiler doesn't give an error.
     * @param fn
     * @param diagnosticSupplier
     * @return
     * @param <U>
     */
    public <U> DiagnosticTrackableCompletableFuture<D, U>
    getDeferredFutureThroughHandle(
            @NonNull BiFunction<? super T, Throwable, ? extends DiagnosticTrackableCompletableFuture<D, U>> fn,
            @NonNull  Supplier<D> diagnosticSupplier) {
        var innerComposedCompletableFutureReference = new AtomicReference<DiagnosticTrackableCompletableFuture<D,U>>();
        CompletableFuture<? extends DiagnosticTrackableCompletableFuture<D, U>> handledFuture =
                this.future.handle((v, t)->{
                    var innerFuture = fn.apply(v,t);
                    innerComposedCompletableFutureReference.set(innerFuture);
                    return innerFuture;
                });
        var newCf = handledFuture.thenCompose(wcf->wcf.future);
        var wrappedDiagnosticFuture =
                new DiagnosticTrackableCompletableFuture<>(newCf, diagnosticSupplier, diagnosticSupplierChain);
        wrappedDiagnosticFuture.innerComposedPendingCompletableFutureReference = innerComposedCompletableFutureReference;
        wrappedDiagnosticFuture.future.whenComplete((v2,t2)->innerComposedCompletableFutureReference.set(null));
        return wrappedDiagnosticFuture;
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    handle(@NonNull BiFunction<? super T, Throwable, ? extends U> fn,
                             @NonNull  Supplier<D> diagnosticSupplier) {
        CompletableFuture<U> newCf = this.future.handle((v, t)->fn.apply(v,t));
        return new DiagnosticTrackableCompletableFuture<>(newCf, diagnosticSupplier, diagnosticSupplierChain);
    }

    public T get() throws ExecutionException, InterruptedException {
        return future.get();
    }

    public T get(@NonNull Duration timeout) throws ExecutionException, InterruptedException, TimeoutException {
        var millis = timeout.toMillis() +
                (timeout.minusNanos(timeout.toNanosPart()).equals(timeout) ? 0 : 1); // round up
        return future.get(millis, TimeUnit.MILLISECONDS);
    }

    public Stream<AbstractMap.SimpleEntry<DiagnosticTrackableCompletableFuture,Supplier<D>>> diagnosticStream() {
        var chainHeadReference = new AtomicReference<>(this.diagnosticSupplierChain);
        return IntStream.generate(()->chainHeadReference.get()!=null?1:0)
                .takeWhile(x->x==1)
                .mapToObj(i->{
                    try {
                        return chainHeadReference.get().item;
                    } finally {
                        chainHeadReference.set(chainHeadReference.get().previous);
                    }
                });
    }

    @Override
    public String toString() {
        return formatAsString(x->null);
    }

    public String formatAsString(@NonNull Function<DiagnosticTrackableCompletableFuture,String> resultFormatter) {
        var strList = diagnosticStream().map(kvp->formatFutureWithDiagnostics(kvp, resultFormatter))
                .collect(Collectors.toList());
        return strList.stream().collect(Collectors.joining("<-"));
    }
//"[…]"
    @SneakyThrows
    protected String formatFutureWithDiagnostics(
            @NonNull AbstractMap.SimpleEntry<DiagnosticTrackableCompletableFuture, Supplier<D>> kvp,
            @NonNull Function<DiagnosticTrackableCompletableFuture,String> resultFormatter) {
        var diagnosticInfo = kvp.getValue().get();
        var isDone = kvp.getKey().future.isDone();
        return "[" + System.identityHashCode(kvp.getKey()) + "] " + diagnosticInfo +
                (isDone ? formatWithDefault(resultFormatter, kvp.getKey()) :
                        getPendingString(kvp, resultFormatter));
    }

    private static <D> String
    getPendingString(AbstractMap.SimpleEntry<DiagnosticTrackableCompletableFuture, Supplier<D>> kvp,
                     Function<DiagnosticTrackableCompletableFuture, String> resultFormatter) {
        return Optional.ofNullable(kvp.getKey().innerComposedPendingCompletableFutureReference)
                .map(r -> (DiagnosticTrackableCompletableFuture<D, ?>) r.get())
                .filter(df -> df != null)
                .map(df -> " --[[" + df.formatAsString(resultFormatter) + " ]] ")
                .orElse("[…]");
    }

    private static <D> String formatWithDefault(
            @NonNull Function<DiagnosticTrackableCompletableFuture,String> formatter,
            DiagnosticTrackableCompletableFuture df) {
        var str = formatter.apply(df);
        return "[" + (str == null ? "^" : str) + "]";
    }
}
