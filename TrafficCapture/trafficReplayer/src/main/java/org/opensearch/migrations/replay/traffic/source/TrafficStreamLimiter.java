package org.opensearch.migrations.replay.traffic.source;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;

@Slf4j
public class TrafficStreamLimiter {

    public final Semaphore liveTrafficStreamCostGate;

    public TrafficStreamLimiter(int maxConcurrentCost) {
        this.liveTrafficStreamCostGate = new Semaphore(maxConcurrentCost);
    }

    public void addWork(int cost) {
        log.atDebug().setMessage(()->"liveTrafficStreamCostGate.permits: {} acquiring: {}")
                .addArgument(liveTrafficStreamCostGate.availablePermits())
                .addArgument(cost)
                .log();
        try {
            liveTrafficStreamCostGate.acquire(cost);
            log.atDebug().setMessage(()->"Acquired liveTrafficStreamCostGate (available=" +
                    liveTrafficStreamCostGate.availablePermits()+")").log();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public void doneProcessing(int cost) {
        liveTrafficStreamCostGate.release(cost);
        log.atDebug().setMessage(()->"released "+cost+
                " liveTrafficStreamCostGate.availablePermits="+liveTrafficStreamCostGate.availablePermits()).log();
    }
}
