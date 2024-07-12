package org.opensearch.migrations.testutils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Streams;

public class StreamInterleaver {

    public static <T> Stream<T> randomlyInterleaveStreams(Random r, Stream<Stream<T>> orderedItemStreams) {
        List<Iterator<T>> iteratorList = orderedItemStreams.map(Stream::iterator)
            .filter(it -> it.hasNext())
            .collect(Collectors.toCollection(() -> new ArrayList<>()));
        return Streams.stream(new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return !iteratorList.isEmpty();
            }

            @Override
            public T next() {
                var slotIdx = r.nextInt(iteratorList.size());
                var collectionIterator = iteratorList.get(slotIdx);
                var nextItem = collectionIterator.next();
                if (!collectionIterator.hasNext()) {
                    var lastIdx = iteratorList.size() - 1;
                    iteratorList.set(slotIdx, iteratorList.get(lastIdx));
                    iteratorList.remove(lastIdx);
                }
                return nextItem;
            }
        });
    }

}
