package org.opensearch.migrations.utils;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * This class wraps CompletableFutures into traceable and identifiable pieces so that when
 * dealing with thousands of completable futures at a time, one can clearly understand how
 * work is proceeding and why there may be issues.
 *
 * This is adding a great amount of clarity, though using it may still be a challenge.  Much
 * more work is expected to improve the UX for developers.
 * @param <D> The type of object that will be returned to represent diagnostic information
 * @param <T> The type of value of the underlying (internal) CompletableFuture's result
 */
@Slf4j
public class TrackedFuture<D, T> {

    public final CompletableFuture<T> future;
    protected AtomicReference<TrackedFuture<D, T>> innerComposedPendingCompletableFutureReference;
    @Getter
    public final Supplier<D> diagnosticSupplier;
    protected final AtomicReference<TrackedFuture<D, ?>> parentDiagnosticFutureRef;

    private TrackedFuture() {
        throw new IllegalCallerException();
    }

    /**
     * This factory class is here so that subclasses can write their own versions that return objects
     * of their own subclass.
     */
    public static class Factory {
        private Factory() {}

        public static <T, D> TrackedFuture<D, T> failedFuture(
            @NonNull Throwable e,
            @NonNull Supplier<D> diagnosticSupplier
        ) {
            return new TrackedFuture<>(CompletableFuture.failedFuture(e), diagnosticSupplier, null);
        }

        public static <U, D> TrackedFuture<D, U> completedFuture(U v, @NonNull Supplier<D> diagnosticSupplier) {
            return new TrackedFuture<>(CompletableFuture.completedFuture(v), diagnosticSupplier, null);
        }
    }

    private TrackedFuture(
        @NonNull CompletableFuture<T> future,
        @NonNull Supplier<D> diagnosticSupplier,
        TrackedFuture<D, ?> parentFuture
    ) {
        this.future = future;
        this.diagnosticSupplier = diagnosticSupplier;
        this.parentDiagnosticFutureRef = new AtomicReference<>();
        setParentDiagnosticFuture(parentFuture);
    }

    public TrackedFuture(@NonNull CompletableFuture<T> future, @NonNull Supplier<D> diagnosticSupplier) {
        this(future, diagnosticSupplier, null);
    }

    public static Throwable unwindPossibleCompletionException(Throwable t) {
        while (t instanceof CompletionException) {
            t = t.getCause();
        }
        return t;
    }

    public TrackedFuture<D, ?> getParentDiagnosticFuture() {
        var p = parentDiagnosticFutureRef.get();
        if (future.isDone() && p != null) {
            p.setParentDiagnosticFuture(null);
        }
        return p;
    }

    protected void setParentDiagnosticFuture(TrackedFuture<D, ?> parent) {
        if (parent == null) {
            parentDiagnosticFutureRef.set(null);
            return;
        }
        var wasSet = parentDiagnosticFutureRef.compareAndSet(null, parent);
        if (!wasSet) {
            throw new IllegalStateException(
                "dependencyDiagnosticFutureRef was already set to " + parentDiagnosticFutureRef.get()
            );
        }
        // the parent is a pretty good breadcrumb for the current stack... but the grandparent of the most recently
        // finished ancestor begins to have diminished value immediately, so cut the ancestry tree at this point
        future.whenComplete(
            (v, t) -> Optional.ofNullable(getParentDiagnosticFuture()).ifPresent(p -> p.setParentDiagnosticFuture(null))
        );
    }

    /**
     * @throws IllegalStateException if the dependentFuture has already been passed to this method
     * before or if it has already been marked as completed or was initialized with a parent.
     */
    public TrackedFuture<D, T> propagateCompletionToDependentFuture(
        TrackedFuture<D, ?> dependentFuture,
        BiConsumer<CompletableFuture<T>, CompletableFuture<?>> consume,
        @NonNull Supplier<D> diagnosticSupplier
    ) {
        dependentFuture.setParentDiagnosticFuture(this);
        return this.whenComplete((v, t) -> consume.accept(this.future, dependentFuture.future), diagnosticSupplier);
    }

    public TrackedFuture<D, T> getInnerComposedPendingCompletableFuture() {
        return Optional.ofNullable(innerComposedPendingCompletableFutureReference)
            .map(AtomicReference::get)
            .orElse(null);
    }

    public <U> TrackedFuture<D, U> map(
        @NonNull Function<CompletableFuture<T>, CompletableFuture<U>> fn,
        @NonNull Supplier<D> diagnosticSupplier
    ) {
        var newCf = fn.apply(future);
        return new TrackedFuture<>(newCf, diagnosticSupplier, this);
    }

    public TrackedFuture<D, Void> thenAccept(Consumer<T> fn, @NonNull Supplier<D> diagnosticSupplier) {
        return this.map(cf -> cf.thenAccept(fn), diagnosticSupplier);
    }

    public <U> TrackedFuture<D, U> thenApply(Function<T, U> fn, @NonNull Supplier<D> diagnosticSupplier) {
        return this.map(cf -> cf.thenApply(fn), diagnosticSupplier);
    }

    public TrackedFuture<D, T> exceptionally(Function<Throwable, T> fn, @NonNull Supplier<D> diagnosticSupplier) {
        return this.map(cf -> cf.exceptionally(fn), diagnosticSupplier);
    }

    public TrackedFuture<D, T> whenComplete(
        BiConsumer<? super T, Throwable> fn,
        @NonNull Supplier<D> diagnosticSupplier
    ) {
        return map(cf -> cf.whenComplete(fn::accept), diagnosticSupplier);
    }

    public <U> TrackedFuture<D, U> thenCompose(
        @NonNull Function<? super T, ? extends TrackedFuture<D, U>> fn,
        @NonNull Supplier<D> diagnosticSupplier
    ) {
        var innerComposedCompletableFutureReference = new AtomicReference<TrackedFuture<D, U>>();
        var newCf = this.future.thenCompose(v -> {
            var innerFuture = fn.apply(v);
            innerComposedCompletableFutureReference.set(innerFuture);
            return innerFuture.future;
        });
        var wrappedDiagnosticFuture = new TrackedFuture<>(newCf, diagnosticSupplier, this);
        wrappedDiagnosticFuture.innerComposedPendingCompletableFutureReference =
            innerComposedCompletableFutureReference;
        wrappedDiagnosticFuture.future.whenComplete((v2, t2) -> innerComposedCompletableFutureReference.set(null));
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
    public <U> TrackedFuture<D, U> getDeferredFutureThroughHandle(
        @NonNull BiFunction<? super T, Throwable, ? extends TrackedFuture<D, U>> fn,
        @NonNull Supplier<D> diagnosticSupplier
    ) {
        var innerComposedCompletableFutureReference = new AtomicReference<TrackedFuture<D, U>>();
        CompletableFuture<? extends TrackedFuture<D, U>> handledFuture = this.future.handle((v, t) -> {
            var innerFuture = fn.apply(v, t);
            innerComposedCompletableFutureReference.set(innerFuture);
            return innerFuture;
        });
        var newCf = handledFuture.thenCompose(wcf -> wcf.future);
        var wrappedDiagnosticFuture = new TrackedFuture<>(newCf, diagnosticSupplier, this);
        wrappedDiagnosticFuture.innerComposedPendingCompletableFutureReference =
            innerComposedCompletableFutureReference;
        // TODO: Add a count to how many futures have been completed and are falling away?
        wrappedDiagnosticFuture.future.whenComplete((v2, t2) -> innerComposedCompletableFutureReference.set(null));
        return wrappedDiagnosticFuture;
    }

    public <U> TrackedFuture<D, U> handle(
        @NonNull BiFunction<? super T, Throwable, ? extends U> fn,
        @NonNull Supplier<D> diagnosticSupplier
    ) {
        CompletableFuture<U> newCf = this.future.handle(fn);
        return new TrackedFuture<>(newCf, diagnosticSupplier, this);
    }

    public T get() throws ExecutionException, InterruptedException {
        return future.get();
    }

    public T get(@NonNull Duration timeout) throws ExecutionException, InterruptedException, TimeoutException {
        var millis = timeout.toMillis() + (timeout.minusNanos(timeout.toNanosPart()).equals(timeout) ? 0 : 1); // round
                                                                                                               // up
        return future.get(millis, TimeUnit.MILLISECONDS);
    }

    public boolean isDone() {
        return future.isDone();
    }

    public Stream<TrackedFuture<D, ?>> walkParentsAsStream() {
        AtomicReference<TrackedFuture<D, ?>> chainHeadReference = new AtomicReference<>(this);
        return IntStream.generate(() -> chainHeadReference.get() != null ? 1 : 0).takeWhile(x -> x == 1).mapToObj(i -> {
            var trackedFuture = chainHeadReference.get();
            chainHeadReference.set(trackedFuture.getParentDiagnosticFuture());
            return trackedFuture;
        });
    }

    @Override
    public String toString() {
        return formatAsString(x -> null);
    }

    public String formatAsString(@NonNull Function<TrackedFuture<D, ?>, String> resultFormatter) {
        return TrackedFutureStringFormatter.format(this, resultFormatter);
    }
}
