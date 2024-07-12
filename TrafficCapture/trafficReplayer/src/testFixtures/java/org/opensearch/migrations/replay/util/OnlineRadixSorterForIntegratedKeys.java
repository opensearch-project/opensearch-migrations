package org.opensearch.migrations.replay.util;

import java.util.function.ToIntFunction;

import lombok.extern.slf4j.Slf4j;

/**
 * This class is a convenience wrapper for OnlineRadixSorter where the items will intrinsically
 * contain their own sorting keys, which are resolved by a helper function that is passed to
 * the constructor.
 *
 * @param <T>
 */
@Slf4j
public class OnlineRadixSorterForIntegratedKeys<T> extends OnlineRadixSorter {

    ToIntFunction<T> radixResolver;

    public OnlineRadixSorterForIntegratedKeys(int startingOffset, ToIntFunction<T> radixResolver) {
        super(startingOffset);
        this.radixResolver = radixResolver;
    }

    public TrackedFuture<String, Void> add(T item, Runnable sortedItemVisitor) {
        return super.addFutureForWork(
            radixResolver.applyAsInt(item),
            signalFuture -> signalFuture.map(
                f -> f.whenComplete((v, t) -> sortedItemVisitor.run()),
                () -> "OnlineRadixSorterForIntegratedKeys.addFutureForWork"
            )
        );
    }
}
