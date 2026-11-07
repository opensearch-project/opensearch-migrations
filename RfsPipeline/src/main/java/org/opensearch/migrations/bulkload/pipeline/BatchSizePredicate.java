package org.opensearch.migrations.bulkload.pipeline;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Starts a new batch before an item would exceed either limit.
 * Use with {@code bufferUntil(predicate, true)} and create one predicate per subscription.
 * An item larger than the byte limit is kept in a batch by itself.
 */
public final class BatchSizePredicate<T> implements Predicate<T> {
    private final int maxCount;
    private final long maxBytes;
    private final ToLongFunction<T> sizeOf;
    private int count;
    private long bytes;

    public BatchSizePredicate(int maxCount, long maxBytes, ToLongFunction<T> sizeOf) {
        if (maxCount < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("Batch count and byte limits must be positive");
        }
        this.maxCount = maxCount;
        this.maxBytes = maxBytes;
        this.sizeOf = Objects.requireNonNull(sizeOf);
    }

    @Override
    public boolean test(T item) {
        long nextBytes = sizeOf.applyAsLong(item);
        if (nextBytes < 0) {
            throw new IllegalArgumentException("Item size must be nonnegative");
        }
        // Subtract instead of adding so the comparison cannot overflow.
        boolean cutBefore = count > 0
            && (count >= maxCount || bytes > maxBytes || nextBytes > maxBytes - bytes);
        if (cutBefore) {
            count = 0;
            bytes = 0;
        }
        count++;
        bytes += nextBytes;
        return cutBefore;
    }
}
