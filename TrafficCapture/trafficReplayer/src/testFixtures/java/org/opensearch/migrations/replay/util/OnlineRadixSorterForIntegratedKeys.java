package org.opensearch.migrations.replay.util;

import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * This class is a convenience wrapper for OnlineRadixSorter where the items will intrinsically
 * contain their own sorting keys, which are resolved by a helper function that is passed to
 * the constructor.
 *
 * @param <T>
 */
public class OnlineRadixSorterForIntegratedKeys<T> extends OnlineRadixSorter {

    ToIntFunction<T> radixResolver;

    public OnlineRadixSorterForIntegratedKeys(int startingOffset, ToIntFunction<T> radixResolver) {
        super(startingOffset);
        this.radixResolver = radixResolver;
    }

    public void add(T item, Runnable sortedItemVisitor) {
        super.addFutureForWork(radixResolver.applyAsInt(item), signalFuture->signalFuture.map(
                f->f.whenComplete((v,t)->sortedItemVisitor.run()),
                ()->"OnlineRadixSorterForIntegratedKeys.add"));
    }
}
