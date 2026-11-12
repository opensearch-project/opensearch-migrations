package org.opensearch.migrations.bulkload.pipeline;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.opensearch.migrations.bulkload.pipeline.model.Document;

import reactor.core.publisher.Mono;

/**
 * Owns source-document byte permits from materialization through target completion.
 *
 * <p>Lucene sources reserve the declared maximum before reading a stored document, shrink
 * that reservation to the actual serialized size, and transfer the reservation to the
 * source-agnostic {@link Document}. Other sources use {@link #admit(Document)} after
 * materialization, which still bounds downstream retention.
 */
public final class SourceDocumentMemoryTracker {
    private final long maxDocumentBytes;
    private final int readParallelism;
    private final WeightedByteLimiter limiter;
    private final IdentityHashMap<Object, WeightedByteLimiter.Permit> permits = new IdentityHashMap<>();

    public SourceDocumentMemoryTracker(
        long maxDocumentBytes,
        int readParallelism,
        WeightedByteLimiter limiter
    ) {
        if (maxDocumentBytes < 1) {
            throw new IllegalArgumentException("maxDocumentBytes must be positive, got " + maxDocumentBytes);
        }
        if (readParallelism < 1) {
            throw new IllegalArgumentException("readParallelism must be positive, got " + readParallelism);
        }
        if (limiter.capacity() < maxDocumentBytes) {
            throw new IllegalArgumentException(
                "source limiter capacity " + limiter.capacity()
                    + " is smaller than maxDocumentBytes " + maxDocumentBytes
            );
        }
        this.maxDocumentBytes = maxDocumentBytes;
        this.readParallelism = readParallelism;
        this.limiter = limiter;
    }

    public int readParallelism() {
        return readParallelism;
    }

    /**
     * Reserve before invoking a source-specific materializer, then retain the exact
     * serialized size until the resulting object is transferred or released.
     */
    public <T> Mono<T> materialize(
        Supplier<T> materializer,
        ToLongFunction<T> serializedSize,
        Function<T, String> documentId
    ) {
        return materialize(Mono.fromSupplier(materializer), serializedSize, documentId);
    }

    public <T> Mono<T> materialize(
        Mono<T> materializer,
        ToLongFunction<T> serializedSize,
        Function<T, String> documentId
    ) {
        return limiter.acquire(maxDocumentBytes).flatMap(permit -> {
            var trackerOwnsPermit = new AtomicBoolean(true);
            return materializer
                .flatMap(value -> {
                    long bytes = serializedSize.applyAsLong(value);
                    validateSize(documentId.apply(value), bytes);
                    permit.shrinkTo(bytes);
                    register(value, permit);
                    trackerOwnsPermit.set(false);
                    return Mono.just(value);
                })
                .doFinally(signal -> {
                    if (trackerOwnsPermit.compareAndSet(true, false)) {
                        permit.release();
                    }
                });
        });
    }

    /** Admit a document from a source that cannot reserve before materialization. */
    public Mono<Document> admit(Document document) {
        long bytes = document.sourceLength();
        validateSize(document.id(), bytes);
        return limiter.acquire(bytes).flatMap(permit -> {
            try {
                register(document, permit);
                return Mono.just(document);
            } catch (RuntimeException | Error e) {
                permit.release();
                return Mono.error(e);
            }
        });
    }

    /**
     * Map a tracked source-specific object to another identity without releasing its permit.
     * Mapping failures release the permit immediately.
     */
    public <S, T> T mapTracked(S source, Function<S, T> mapper) {
        var permit = remove(source);
        if (permit == null) {
            throw new IllegalStateException("No source-memory permit is registered for the mapped object");
        }
        try {
            T target = mapper.apply(source);
            register(target, permit);
            return target;
        } catch (RuntimeException | Error e) {
            permit.release();
            throw e;
        }
    }

    public void release(Object trackedObject) {
        var permit = remove(trackedObject);
        if (permit != null) {
            permit.release();
        }
    }

    public void release(List<? extends Document> documents) {
        documents.forEach(this::release);
    }

    public void releaseAll() {
        List<WeightedByteLimiter.Permit> remaining;
        synchronized (permits) {
            remaining = List.copyOf(permits.values());
            permits.clear();
        }
        remaining.forEach(WeightedByteLimiter.Permit::release);
    }

    private void validateSize(String documentId, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException(
                "Source document '" + documentId + "' has a negative serialized size " + bytes
            );
        }
        if (bytes > maxDocumentBytes) {
            throw new IllegalArgumentException(
                "Source document '" + documentId + "' is " + bytes
                    + " serialized bytes, over maxSourceDocumentSizeBytes " + maxDocumentBytes
            );
        }
    }

    private void register(Object trackedObject, WeightedByteLimiter.Permit permit) {
        if (trackedObject == null) {
            throw new IllegalArgumentException("A source-memory permit cannot track a null object");
        }
        synchronized (permits) {
            var previous = permits.put(trackedObject, permit);
            if (previous != null) {
                permits.put(trackedObject, previous);
                throw new IllegalStateException(
                    "Source emitted the same object more than once before completion"
                );
            }
        }
    }

    private WeightedByteLimiter.Permit remove(Object trackedObject) {
        synchronized (permits) {
            return permits.remove(trackedObject);
        }
    }
}
