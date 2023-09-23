package org.opensearch.migrations.replay.datatypes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

class TimeToResponseFulfillmentFutureMapTest {
    @Test
    public void testAddsAndPopsAreOrdered() {
        var timeMap = new TimeToResponseFulfillmentFutureMap();
        StringBuilder log = new StringBuilder();
        timeMap.appendTask(Instant.EPOCH, ()->log.append('A'));
        timeMap.appendTask(Instant.EPOCH, ()->log.append('B'));
        timeMap.appendTask(Instant.EPOCH.plus(Duration.ofMillis(1)), ()->log.append('C'));
        timeMap.appendTask(Instant.EPOCH.plus(Duration.ofMillis(1)), ()->log.append('D'));
        while (true) {
            var t = timeMap.peekFirstItem();
            if (t == null) {
                break;
            }
            t.getValue().run();
            timeMap.removeFirstItem();
        }
        Assertions.assertEquals("ABCD", log.toString());
    }
}