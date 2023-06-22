package org.opensearch.migrations.replay.util;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DiagnosticTrackableCompletableFuture<D, T> {
    public final CompletableFuture<T> future;
    private final RecursiveImmutableChain<Supplier<D>> diagnosticSupplierChain;

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

    public DiagnosticTrackableCompletableFuture(CompletableFuture<T> future,
                                                RecursiveImmutableChain<Supplier<D>> diagnosticSupplierChain) {
        this.future = future;
        this.diagnosticSupplierChain = diagnosticSupplierChain;
    }

    public DiagnosticTrackableCompletableFuture(CompletableFuture<T> future, Supplier<D> diagnosticSupplier) {
        this(future, new RecursiveImmutableChain(diagnosticSupplier, null));
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    map(Function<CompletableFuture<T>, CompletableFuture<U>> fn, Supplier<D> diagnosticSupplier) {
        return new DiagnosticTrackableCompletableFuture<>(fn.apply(future),
                this.diagnosticSupplierChain.chain(diagnosticSupplier));
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    thenCompose(Function<? super T, ? extends DiagnosticTrackableCompletableFuture<D, U>> fn, Supplier<D> diagnosticSupplier) {
        return new DiagnosticTrackableCompletableFuture<>(this.future.thenCompose(v->fn.apply(v).future),
                this.diagnosticSupplierChain.chain(diagnosticSupplier));
    }

    public <U> DiagnosticTrackableCompletableFuture<D, U>
    handle(BiFunction<? super T, Throwable, ? extends DiagnosticTrackableCompletableFuture<D, U>> fn,
           Supplier<D> diagnosticSupplier) {
        return new DiagnosticTrackableCompletableFuture<>(this.future.handle((v, t)->fn.apply(v,t))
                .thenCompose(wcf->wcf.future), this.diagnosticSupplierChain.chain(diagnosticSupplier));
    }

    public T get() throws ExecutionException, InterruptedException {
        return future.get();
    }

    public Stream<Supplier<D>> diagnosticStream() {
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
        return diagnosticStream().map(s->s.get().toString()).collect(Collectors.joining("->"));
    }
}
