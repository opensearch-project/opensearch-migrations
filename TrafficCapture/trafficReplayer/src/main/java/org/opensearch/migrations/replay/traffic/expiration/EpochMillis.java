package org.opensearch.migrations.replay.traffic.expiration;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

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

*/
// REBUILD-LIMBO-END(G11)