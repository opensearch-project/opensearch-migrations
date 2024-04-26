package org.opensearch.migrations.replay.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
class OnlineRadixSorterTest {

    private static String stringify(Stream<Integer> stream) {
        return stream.map(Object::toString).collect(Collectors.joining(","));
    }

    private static String add(OnlineRadixSorterForIntegratedKeys<Integer> sorter,
                              Map<Integer, DiagnosticTrackableCompletableFuture<String,Void>> m,
                              ArrayList<Integer> receivedItems, int v) {
        var dcf = sorter.add(v, () -> receivedItems.add(v));
        if (m != null) { m.put(v, dcf); }
        log.atInfo().setMessage(()->"after adding work... "+dcf).log();
        return stringify(receivedItems.stream());
    }

    @Test
    void testOnlineRadixSorter_inOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i -> (int) i);
        Assertions.assertEquals("1", add(radixSorter, null, new ArrayList<Integer>(), 1));
        Assertions.assertEquals("2", add(radixSorter, null, new ArrayList<Integer>(), 2));
        Assertions.assertEquals("3", add(radixSorter, null, new ArrayList<Integer>(), 3));
    }

    @Test
    void testOnlineRadixSorter_outOfOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i->(int) i);
        var receiverList = new ArrayList<Integer>();
        var dcfMap = new HashMap<Integer,DiagnosticTrackableCompletableFuture<String,Void>>();
        Assertions.assertEquals("", add(radixSorter, dcfMap, receiverList, 3));
        Assertions.assertEquals("", add(radixSorter, dcfMap, receiverList, 4));
        Assertions.assertEquals("1", add(radixSorter, dcfMap, receiverList, 1));
        log.atInfo().setMessage(()->"after adding work for '1'... dcf[3]=" + dcfMap.get(3)).log();
        log.atInfo().setMessage(()->"after adding work for '1'... dcf[4]=" + dcfMap.get(4)).log();
        receiverList.clear();
        Assertions.assertEquals("2,3,4", add(radixSorter, dcfMap, receiverList, 2));
        receiverList.clear();
        Assertions.assertEquals("5", add(radixSorter, dcfMap, receiverList, 5));
        receiverList.clear();
        Assertions.assertEquals("", add(radixSorter, dcfMap, receiverList, 7));
        receiverList.clear();
    }

    @Test
    void testGetAwaitingStrings() {
        var radixSorter = new OnlineRadixSorter(1);
        radixSorter.addFutureForWork(4, x -> x);
        Assertions.assertEquals("slotsOutstanding: >4,3-1", radixSorter.getAwaitingText());
        radixSorter.addFutureForWork(6, x -> x);
        Assertions.assertEquals("slotsOutstanding: >6,5,3-1", radixSorter.getAwaitingText());
        for (int i = 9; i < 20; ++i) {
            radixSorter.addFutureForWork(i, x -> x);
        }
        Assertions.assertEquals("slotsOutstanding: >19,8-7,5,3-1", radixSorter.getAwaitingText());
    }
}