package org.opensearch.migrations.replay.datatypes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import java.time.Duration;
import java.time.Instant;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class TimeToResponseFulfillmentFutureMapTest {
    @Test
    public void testAddsAndPopsAreOrdered() {
        var timeMap = new TimeToResponseFulfillmentFutureMap();
        StringBuilder log = new StringBuilder();
        timeMap.appendTask(Instant.EPOCH, new ChannelTask(ChannelTaskType.TRANSMIT, ()->log.append('A')));
        timeMap.appendTask(Instant.EPOCH, new ChannelTask(ChannelTaskType.TRANSMIT, ()->log.append('B')));
        timeMap.appendTask(Instant.EPOCH.plus(Duration.ofMillis(1)), new ChannelTask(ChannelTaskType.TRANSMIT, ()->log.append('C')));
        timeMap.appendTask(Instant.EPOCH.plus(Duration.ofMillis(1)), new ChannelTask(ChannelTaskType.TRANSMIT, ()->log.append('D')));
        while (true) {
            var t = timeMap.peekFirstItem();
            if (t == null) {
                break;
            }
            t.getValue().runnable.run();
            timeMap.removeFirstItem();
        }
        Assertions.assertEquals("ABCD", log.toString());
    }
}