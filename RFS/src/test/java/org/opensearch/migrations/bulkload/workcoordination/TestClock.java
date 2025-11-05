package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

class TestClock extends Clock {
    private Instant currentInstant;

    TestClock(Instant initialInstant) {
        this.currentInstant = initialInstant;
    }

    void advance(Duration duration) {
        currentInstant = currentInstant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return currentInstant;
    }
}
