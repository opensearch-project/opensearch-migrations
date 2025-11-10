package org.opensearch.migrations.replay.traffic.expiration;

import java.time.Instant;
import java.util.function.BiPredicate;

import lombok.EqualsAndHashCode;

@SuppressWarnings("java:S1210")
@EqualsAndHashCode
class EpochMillis implements Comparable<EpochMillis> {
    final long millis;

    public EpochMillis(Instant i) {
        millis = i.toEpochMilli();
    }

    public EpochMillis(long ms) {
        this.millis = ms;
    }

    public boolean test(EpochMillis referenceTimestamp, BiPredicate<Long, Long> c) {
        return c.test(this.millis, referenceTimestamp.millis);
    }

    public boolean test(Instant referenceTimestamp, BiPredicate<Long, Long> c) {
        return c.test(this.millis, referenceTimestamp.toEpochMilli());
    }

    public Instant toInstant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public String toString() {
        return Long.toString(millis);
    }

    @Override
    public int compareTo(EpochMillis o) {
        return Long.compare(this.millis, o.millis);
    }
}
