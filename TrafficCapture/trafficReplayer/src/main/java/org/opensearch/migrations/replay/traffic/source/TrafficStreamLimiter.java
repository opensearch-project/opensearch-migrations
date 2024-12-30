package org.opensearch.migrations.replay.traffic.source;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrafficStreamLimiter implements AutoCloseable {

    @AllArgsConstructor
    public static class WorkItem {
        private final @NonNull Consumer<WorkItem> task;
        private final IHttpTransactionContext context;
        private final int cost;
    }

    public final Semaphore liveTrafficStreamCostGate;
    private final LinkedTransferQueue<WorkItem> workQueue;
    private final Thread consumerThread;
    private final AtomicBoolean stopped;

    public TrafficStreamLimiter(int maxConcurrentCost) {
        this.liveTrafficStreamCostGate = new Semaphore(maxConcurrentCost);
        this.workQueue = new LinkedTransferQueue<>();
        this.stopped = new AtomicBoolean();
        this.consumerThread = new Thread(this::consumeFromQueue, "requestFeederThread");
        this.consumerThread.start();
    }

    public boolean isStopped() {
        return stopped.get();
    }

    @SneakyThrows
    private void consumeFromQueue() {
        WorkItem workItem = null;
        try {
            while (!stopped.get()) {
                workItem = workQueue.take();
                log.atDebug().setMessage("liveTrafficStreamCostGate.permits: {} acquiring: {}")
                    .addArgument(liveTrafficStreamCostGate::availablePermits)
                    .addArgument(workItem.cost)
                    .log();
                liveTrafficStreamCostGate.acquire(workItem.cost);
                WorkItem finalWorkItem = workItem;
                log.atDebug().setMessage("Acquired liveTrafficStreamCostGate (available={}) to process {}")
                    .addArgument(finalWorkItem.context)
                    .addArgument(liveTrafficStreamCostGate::availablePermits)
                    .log();
                workItem.task.accept(workItem);
                workItem = null;
            }
        } catch (InterruptedException e) {
            if (!stopped.get()) {
                WorkItem finalWorkItem = workItem;
                log.atError().setMessage("consumeFromQueue() was interrupted with {} enqueued items")
                    .addArgument(() -> (finalWorkItem != null ? "an active task and " : ""))
                    .addArgument(workQueue::size)
                    .log();
            }
            throw e;
        }
    }

    public WorkItem queueWork(int cost, IHttpTransactionContext context, @NonNull Consumer<WorkItem> task) {
        var workItem = new WorkItem(task, context, cost);
        var rval = workQueue.offer(workItem);
        assert rval;
        return workItem;
    }

    public void doneProcessing(@NonNull WorkItem workItem) {
        liveTrafficStreamCostGate.release(workItem.cost);
        log.atDebug().setMessage("released {} liveTrafficStreamCostGate.availablePermits={} for {}")
            .addArgument(workItem.cost)
            .addArgument(liveTrafficStreamCostGate::availablePermits)
            .addArgument(workItem.context)
            .log();
    }

    @Override
    public void close() throws Exception {
        stopped.set(true);
        this.consumerThread.interrupt();
        this.consumerThread.join();
    }
}
