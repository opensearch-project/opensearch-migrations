package org.opensearch.migrations.replay.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OnlineRadixSorterTest {

    private static String stringify(Stream<Integer> stream) {
        return stream.map(i->i.toString()).collect(Collectors.joining(","));
    }

    private static String add(OnlineRadixSorterForIntegratedKeys<Integer> sorter, int v) {
        var sortedItems = new ArrayList<Integer>();
        sorter.add(Integer.valueOf(v), i->sortedItems.add(i));
        return sortedItems.stream().map(i->i.toString()).collect(Collectors.joining(","));
    }

    @Test
    void testOnlineRadixSorter_inOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i -> (int) i);
        Assertions.assertEquals("1", add(radixSorter,1));
        Assertions.assertEquals("2", add(radixSorter, 2));
        Assertions.assertEquals("3", add(radixSorter, 3));
    }

    @Test
    void testOnlineRadixSorter_outOfOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i->(int) i);
        Assertions.assertEquals("", add(radixSorter, 3));
        Assertions.assertEquals("", add(radixSorter, 4));
        Assertions.assertEquals("1", add(radixSorter, 1));
        Assertions.assertEquals("2,3,4", add(radixSorter, 2));
        Assertions.assertEquals("5", add(radixSorter, 5));
        Assertions.assertEquals("", add(radixSorter, 7));
    }
}