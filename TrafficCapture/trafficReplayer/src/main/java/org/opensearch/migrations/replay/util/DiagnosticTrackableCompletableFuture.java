package org.opensearch.migrations.replay.util;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
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
    private final RecursiveImmutableChain<AbstractMap.SimpleEntry<CompletableFuture,Supplier<D>>> diagnosticSupplierChain;

    /**
     * This factory class is here so that subclasses can write their own versions that return objects
     * of their own subclass.
     */
    public static class factory {
        public static <T, D> DiagnosticTrackableCompletableFuture<D, T>
        failedFuture(Exception e, Supplier<D> diagnosticSupplier) {
            return new DiagnosticTrackableCompletableFuture<>(CompletableFuture.failedFuture(e), diagnosticSupplier);
        }

        public static <U, D> DiagnosticTrackableCompletableFuture<D, U>
        completedFuture(U v, Supplier<D> diagnosticSupplier) {
            return new DiagnosticTrackableCompletableFuture<>(CompletableFuture.completedFuture(v), diagnosticSupplier);
        }

        public static <D> DiagnosticTrackableCompletableFuture<D, Void>
        allOf(DiagnosticTrackableCompletableFuture<D,Void>[] allRemainingWorkArray, Supplier<D> diagnosticSupplier) {
            return new DiagnosticTrackableCompletableFuture<>(
                    CompletableFuture.allOf(Arrays.stream(allRemainingWorkArray)
                            .map(tcf->tcf.future).toArray(CompletableFuture[]::new)),
                    diagnosticSupplier);
        }
    }

    private DiagnosticTrackableCompletableFuture(
            @NonNull CompletableFuture<T> future,
            RecursiveImmutableChain<AbstractMap.SimpleEntry<CompletableFuture,Supplier<D>>> diagnosticSupplierChain) {
        this.future = future;
        this.diagnosticSupplierChain = diagnosticSupplierChain;
    }

    public DiagnosticTrackableCompletableFuture(@NonNull CompletableFuture<T> future, Supplier<D> diagnosticSupplier) {
        this(future, new RecursiveImmutableChain(makeDiagnosticPair(future, diagnosticSupplier), null));
    }

    private static <T,D> AbstractMap.SimpleEntry<CompletableFuture,Supplier<D>>
    makeDiagnosticPair(CompletableFuture<T> future, Supplier<D> diagnosticSupplier) {
        return new AbstractMap.SimpleEntry(future, diagnosticSupplier);
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    map(Function<CompletableFuture<T>, CompletableFuture<U>> fn, Supplier<D> diagnosticSupplier) {
        var newCf = fn.apply(future);
        return new DiagnosticTrackableCompletableFuture<>(newCf,
                this.diagnosticSupplierChain.chain(makeDiagnosticPair(newCf, diagnosticSupplier)));
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    thenCompose(Function<? super T, ? extends DiagnosticTrackableCompletableFuture<D, U>> fn,
                Supplier<D> diagnosticSupplier) {
        var newCf = this.future.thenCompose(v->fn.apply(v).future);
        return new DiagnosticTrackableCompletableFuture<>(newCf,
                this.diagnosticSupplierChain.chain(makeDiagnosticPair(newCf, diagnosticSupplier)));
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
    composeHandleApplication(BiFunction<? super T, Throwable, ? extends DiagnosticTrackableCompletableFuture<D, U>> fn,
                             Supplier<D> diagnosticSupplier) {
        CompletableFuture<? extends DiagnosticTrackableCompletableFuture<D, U>> handledFuture =
                this.future.handle((v, t)->fn.apply(v,t));
        var newCf = handledFuture.thenCompose(wcf->{
            log.error("Got wcf = " + wcf);
            return wcf.future;
        });
        return new DiagnosticTrackableCompletableFuture<>(newCf,
                this.diagnosticSupplierChain.chain(makeDiagnosticPair(newCf, diagnosticSupplier)));
    }

    public T get() throws ExecutionException, InterruptedException {
        return future.get();
    }

    public T get(Duration timeout) throws ExecutionException, InterruptedException, TimeoutException {
        var millis = timeout.toMillis() +
                (timeout.minusNanos(timeout.toNanosPart()).equals(timeout) ? 0 : 1); // round up
        return future.get(millis, TimeUnit.MILLISECONDS);
    }

    public Stream<AbstractMap.SimpleEntry<CompletableFuture,Supplier<D>>> diagnosticStream() {
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

    public String formatAsString(Function<CompletableFuture,String> resultFormatter) {
        var strList = diagnosticStream().map(kvp->formatDiagnostics(kvp, resultFormatter)).collect(Collectors.toList());
        Collections.reverse(strList);
        return strList.stream().collect(Collectors.joining("->"));
    }

    @SneakyThrows
    protected String formatDiagnostics(AbstractMap.SimpleEntry<CompletableFuture, Supplier<D>> kvp,
                                       Function<CompletableFuture,String> resultFormatter) {
        var diagnosticInfo = kvp.getValue().get();
        return "" + diagnosticInfo +
                (kvp.getKey().isDone() ? formatWithDefault(resultFormatter, kvp.getKey()) : "[…]");
    }

    private static String formatWithDefault(Function<CompletableFuture,String> formatter, CompletableFuture cf) {
        var str = formatter.apply(cf);
        return "[" + (str == null ? "^" : str) + "]";
    }
}
