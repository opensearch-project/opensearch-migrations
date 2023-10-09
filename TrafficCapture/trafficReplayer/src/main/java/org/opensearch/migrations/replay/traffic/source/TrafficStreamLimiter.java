package org.opensearch.migrations.replay.traffic.source;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

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
            throw new RuntimeException(e);
        }
    }

    public void doneProcessing(int cost) {
        liveTrafficStreamCostGate.release(cost);
        log.atDebug().setMessage(()->"released "+cost+
                " liveTrafficStreamCostGate.availablePermits="+liveTrafficStreamCostGate.availablePermits()).log();
    }
}
