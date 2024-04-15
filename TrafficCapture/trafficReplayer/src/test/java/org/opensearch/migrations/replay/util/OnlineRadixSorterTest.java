package org.opensearch.migrations.replay.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class OnlineRadixSorterTest {

    private static String stringify(Stream<Integer> stream) {
        return stream.map(i->i.toString()).collect(Collectors.joining(","));
    }

    private static String add(OnlineRadixSorterForIntegratedKeys<Integer> sorter, ArrayList<Integer> receivedItems, int v) {
        sorter.add(v, ()-> receivedItems.add(v));
        return stringify(receivedItems.stream());
    }

    @Test
    void testOnlineRadixSorter_inOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i -> (int) i);
        Assertions.assertEquals("1", add(radixSorter, new ArrayList<Integer>(), 1));
        Assertions.assertEquals("2", add(radixSorter, new ArrayList<Integer>(), 2));
        Assertions.assertEquals("3", add(radixSorter, new ArrayList<Integer>(), 3));
    }

    @Test
    void testOnlineRadixSorter_outOfOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i->(int) i);
        var receiverList = new ArrayList<Integer>();
        Assertions.assertEquals("", add(radixSorter, receiverList, 3));
        Assertions.assertEquals("", add(radixSorter, receiverList, 4));
        Assertions.assertEquals("1", add(radixSorter, receiverList, 1));
        receiverList.clear();
        Assertions.assertEquals("2,3,4", add(radixSorter, receiverList, 2));
        receiverList.clear();
        Assertions.assertEquals("5", add(radixSorter, receiverList, 5));
        receiverList.clear();
        Assertions.assertEquals("", add(radixSorter, receiverList, 7));
        receiverList.clear();
    }
}