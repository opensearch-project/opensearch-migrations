package org.opensearch.migrations.replay.datatypes;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class TimeToResponseFulfillmentFutureMapTest {
    @Test
    public void testAddsAndPopsAreOrdered() throws Exception {
        var timeMap = new TimeToResponseFulfillmentFutureMap();
        StringBuilder log = new StringBuilder();
        timeMap.appendTaskTrigger(Instant.EPOCH, ChannelTaskType.TRANSMIT).scheduleFuture.thenAccept(
            v -> log.append('A'),
            () -> ""
        );
        timeMap.appendTaskTrigger(Instant.EPOCH, ChannelTaskType.TRANSMIT).scheduleFuture.thenAccept(
            v -> log.append('B'),
            () -> ""
        );
        timeMap.appendTaskTrigger(Instant.EPOCH.plus(Duration.ofMillis(1)), ChannelTaskType.TRANSMIT).scheduleFuture
            .thenAccept(v -> log.append('C'), () -> "");
        var lastWorkFuture = timeMap.appendTaskTrigger(
            Instant.EPOCH.plus(Duration.ofMillis(1)),
            ChannelTaskType.TRANSMIT
        ).scheduleFuture.thenAccept(v -> log.append('D'), () -> "");
        while (true) {
            var t = timeMap.peekFirstItem();
            if (t == null) {
                break;
            }
            t.scheduleFuture.future.complete(null);
            timeMap.removeFirstItem();
        }
        lastWorkFuture.get();
        Assertions.assertEquals("ABCD", log.toString());
    }
}
