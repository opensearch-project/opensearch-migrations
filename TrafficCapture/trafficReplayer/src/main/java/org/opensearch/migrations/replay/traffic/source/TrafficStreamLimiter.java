package org.opensearch.migrations.replay.traffic.source;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class TrafficStreamLimiter implements AutoCloseable {


    @AllArgsConstructor
    public static class WorkItem {
        private final Consumer<WorkItem> task;
        private final int cost;
    }


    public final Semaphore liveTrafficStreamCostGate;
    private final LinkedTransferQueue<WorkItem> workQueue;
    private final Thread consumerThread;
    private AtomicBoolean stopped;

    public TrafficStreamLimiter(int maxConcurrentCost) {
        this.liveTrafficStreamCostGate = new Semaphore(maxConcurrentCost);
        this.workQueue = new LinkedTransferQueue<>();
        this.stopped = new AtomicBoolean();
        this.consumerThread = new Thread(this::consumeFromQueue, "requestFeederThread");
        this.consumerThread.start();
    }

    @SneakyThrows
    private void consumeFromQueue() {
        while (!stopped.get()) {
            var workItem = workQueue.take();
            log.atDebug().setMessage(()->"liveTrafficStreamCostGate.permits: {} acquiring: {}")
                    .addArgument(liveTrafficStreamCostGate.availablePermits())
                    .addArgument(workItem.cost)
                    .log();
            liveTrafficStreamCostGate.acquire(workItem.cost);
            log.atDebug().setMessage(()->"Acquired liveTrafficStreamCostGate (available=" +
                    liveTrafficStreamCostGate.availablePermits()+")").log();
            workItem.task.accept(workItem);
        }
    }

    public WorkItem queueWork(int cost, Consumer<WorkItem> task) {
        var workItem = new WorkItem(task, cost);
        var rval = workQueue.offer(workItem);
        assert rval;
        return workItem;
    }

    public void doneProcessing(WorkItem workItem) {
        liveTrafficStreamCostGate.release(workItem.cost);
        log.atDebug().setMessage(()->"released "+workItem.cost+
                " liveTrafficStreamCostGate.availablePermits="+liveTrafficStreamCostGate.availablePermits()).log();
    }

    @Override
    public void close() throws Exception {
        stopped.set(true);
        this.consumerThread.interrupt();
        this.consumerThread.join();
    }
}
