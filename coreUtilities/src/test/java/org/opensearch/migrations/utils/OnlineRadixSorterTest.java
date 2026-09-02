package org.opensearch.migrations.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class OnlineRadixSorterTest {

    private static String stringify(Stream<Integer> stream) {
        return stream.map(Object::toString).collect(Collectors.joining(","));
    }

    private static String add(
        OnlineRadixSorterForIntegratedKeys<Integer> sorter,
        Map<Integer, TrackedFuture<String, Void>> m,
        ArrayList<Integer> receivedItems,
        int v
    ) {
        var future = sorter.add(v, () -> receivedItems.add(v));
        if (m != null) {
            m.put(v, future);
        }
        log.atInfo().setMessage("after adding work... {}").addArgument(future).log();
        return stringify(receivedItems.stream());
    }

    @Test
    void testOnlineRadixSorter_inOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i -> (int) i);
        Assertions.assertEquals("1", add(radixSorter, null, new ArrayList<>(), 1));
        Assertions.assertEquals("2", add(radixSorter, null, new ArrayList<>(), 2));
        Assertions.assertEquals("3", add(radixSorter, null, new ArrayList<>(), 3));
    }

    @Test
    void testOnlineRadixSorter_outOfOrder() {
        var radixSorter = new OnlineRadixSorterForIntegratedKeys(1, i -> (int) i);
        var receiverList = new ArrayList<Integer>();
        var futureMap = new HashMap<Integer, TrackedFuture<String, Void>>();
        Assertions.assertEquals("", add(radixSorter, futureMap, receiverList, 3));
        Assertions.assertEquals("", add(radixSorter, futureMap, receiverList, 4));
        Assertions.assertEquals("1", add(radixSorter, futureMap, receiverList, 1));
        log.atInfo().setMessage("after adding work for '1'... future[3]={}").addArgument(()->futureMap.get(3)).log();
        log.atInfo().setMessage("after adding work for '1'... future[4]={}").addArgument(() -> futureMap.get(4)).log();
        receiverList.clear();
        Assertions.assertEquals("2,3,4", add(radixSorter, futureMap, receiverList, 2));
        receiverList.clear();
        Assertions.assertEquals("5", add(radixSorter, futureMap, receiverList, 5));
        receiverList.clear();
        Assertions.assertEquals("", add(radixSorter, futureMap, receiverList, 7));
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

    @Test
    void failedWorkAllowsNextSlotToStart() throws Exception {
        var sorter = new OnlineRadixSorter(0);
        var activeWork = new TextTrackedFuture<Void>("active work");
        var secondSlotStarted = new AtomicBoolean();

        var firstResult = sorter.addFutureForWork(
            0,
            startSignal -> startSignal.thenCompose(ignored -> activeWork, () -> "starting active work")
        );
        var secondResult = sorter.addFutureForWork(
            1,
            startSignal -> startSignal.thenApply(ignored -> {
                secondSlotStarted.set(true);
                return null;
            }, () -> "starting second slot")
        );

        var failure = new IllegalStateException("failed");
        activeWork.future.completeExceptionally(failure);

        var thrown = Assertions.assertThrows(ExecutionException.class, firstResult::get);
        Assertions.assertSame(failure, thrown.getCause());
        Assertions.assertTrue(secondSlotStarted.get(), "ordinary failure should not cancel later channel work");
        Assertions.assertNull(secondResult.get());
    }

    @Test
    void cancelledWorkDoesNotInvokeNextSlotCallback() {
        var sorter = new OnlineRadixSorter(0);
        var activeWork = new TextTrackedFuture<Void>("active work");
        var secondSlotStarted = new AtomicBoolean();

        sorter.addFutureForWork(
            0,
            startSignal -> startSignal.thenCompose(ignored -> activeWork, () -> "starting active work")
        );
        var secondResult = sorter.addFutureForWork(
            1,
            startSignal -> startSignal.thenApply(ignored -> {
                secondSlotStarted.set(true);
                return null;
            }, () -> "starting second slot")
        );

        var cancellation = new CancellationException("cancelled");
        activeWork.future.completeExceptionally(cancellation);

        Assertions.assertFalse(secondSlotStarted.get(), "cancelled work must not start queued business callbacks");
        var thrown = Assertions.assertThrows(ExecutionException.class, secondResult::get);
        Assertions.assertSame(cancellation, thrown.getCause());
        Assertions.assertTrue(sorter.isEmpty(), "cancelled slots should settle and leave the sorter");
    }

    @Test
    void cancelAllWorkSettlesPlaceholdersWithoutInvokingQueuedCallback() {
        var sorter = new OnlineRadixSorter(0);
        var queuedSlotStarted = new AtomicBoolean();
        var queuedResult = sorter.addFutureForWork(
            2,
            startSignal -> startSignal.thenApply(ignored -> {
                queuedSlotStarted.set(true);
                return null;
            }, () -> "starting queued slot")
        );

        var cancellation = new CancellationException("terminal cancellation");
        sorter.cancelAllWork(cancellation);

        Assertions.assertFalse(queuedSlotStarted.get(), "sorter cancellation must not invoke queued callbacks");
        var thrown = Assertions.assertThrows(ExecutionException.class, queuedResult::get);
        Assertions.assertSame(cancellation, thrown.getCause());
        Assertions.assertTrue(sorter.isEmpty(), "all placeholders should settle during cancellation");
    }
}
