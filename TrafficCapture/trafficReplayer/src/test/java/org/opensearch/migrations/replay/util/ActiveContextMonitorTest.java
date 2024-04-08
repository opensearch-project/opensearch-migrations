package org.opensearch.migrations.replay.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.TestContext;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

class ActiveContextMonitorTest {

    @Test
    void test() throws Exception {
        var loggedLines = new ArrayList<Map.Entry<Level,String>>();
        var globalContextTracker = new ActiveContextTracker();
        var perActivityContextTracker = new ActiveContextTrackerByActivityType();
        var orderedWorkerTracker = new OrderedWorkerTracker();
        var compositeTracker = new CompositeContextTracker(globalContextTracker, perActivityContextTracker);
        var acm = new ActiveContextMonitor(
                globalContextTracker, perActivityContextTracker, orderedWorkerTracker, 10,
                (level, msgSupplier) -> loggedLines.add(Map.entry(level, msgSupplier.get())),
                Map.of(
                        Level.ERROR, Duration.ofMillis(10000),
                        Level.WARN, Duration.ofMillis(80),
                        Level.INFO, Duration.ofMillis(60),
                        Level.DEBUG, Duration.ofMillis(40),
                        Level.TRACE, Duration.ofMillis(20)));
        try (var testContext = TestContext.noOtelTracking()) {
            var channelContext = testContext.getTestConnectionRequestContext(0);
            compositeTracker.onContextCreated(channelContext);
            acm.run();
            Thread.sleep(50);
            acm.run();
            Thread.sleep(50);
            acm.run();
        }
        Assertions.assertEquals(4, loggedLines.size());
    }
}