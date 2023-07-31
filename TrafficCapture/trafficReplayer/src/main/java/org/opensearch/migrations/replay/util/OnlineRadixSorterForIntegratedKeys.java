package org.opensearch.migrations.replay.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * This class is a convenience wrapper for OnlineRadixSorter where the items will intrinsically
 * contain their own sorting keys, which are resolved by a helper function that is passed to
 * the constructor.
 *
 * @param <T>
 */
public class OnlineRadixSorterForIntegratedKeys<T> extends OnlineRadixSorter<T> {

    ToIntFunction<T> radixResolver;

    public OnlineRadixSorterForIntegratedKeys(int startingOffset, ToIntFunction<T> radixResolver) {
        super(startingOffset);
        this.radixResolver = radixResolver;
    }

    public void add(T item, Consumer<T> sortedItemVisitor) {
        super.add(radixResolver.applyAsInt(item), item, sortedItemVisitor);
    }
}
