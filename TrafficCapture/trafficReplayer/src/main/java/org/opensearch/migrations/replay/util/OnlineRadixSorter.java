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
 * This provides a simple implementation to sort incoming elements that are ordered by a sequence
 * of unique and contiguous integers.  This implementation uses an ArrayList for staging out of order
 * elements and the memory utilization will be O(total number of items to be sequenced).
 *
 * After the item has been added, all the next currently sequenced items are passed to the Consumer
 * that was provided to add().  This allows the calling context to visit the items in the natural
 * order as opposed to the order that items were added.  This class maintains a cursor of the last
 * item that was sent so that items are only visited once and so that the class knows which item is
 * the next item in the sequence.
 *
 * As items are visited, the object will drop its reference to the item, but no efforts are made to
 * free its own storage.  The assumption is that this class will be used for small, short-lived
 * data sets. or in cases where the worst-case performance (needing to hold space for all the items)
 * would be common.
 *
 * @param <T>
 */
public class OnlineRadixSorter<T> {
    ArrayList<T> items;
    int currentOffset;

    public OnlineRadixSorter(int startingOffset) {
        items = new ArrayList<>();
        currentOffset = startingOffset;
    }

    public void add(int index, T item, Consumer<T> sortedItemVisitor) {
        if (currentOffset == index) {
            ++currentOffset;
            sortedItemVisitor.accept(item);
            while (currentOffset < items.size()) {
                var nextItem = items.get(currentOffset);
                if (nextItem != null) {
                    items.set(currentOffset, null);
                    ++currentOffset;
                    sortedItemVisitor.accept(nextItem);
                } else {
                    break;
                }
            }
        } else {
            while (index >= items.size()) {
                items.add(null);
            }
            items.set(index, item);
        }
    }

    public boolean hasPending() {
        return currentOffset < items.size();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("OnlineRadixSorter{");
        sb.append("items=").append(items);
        sb.append(", currentOffset=").append(currentOffset);
        sb.append('}');
        return sb.toString();
    }

    public long numPending() {
        return items.size() - currentOffset;
    }
}
