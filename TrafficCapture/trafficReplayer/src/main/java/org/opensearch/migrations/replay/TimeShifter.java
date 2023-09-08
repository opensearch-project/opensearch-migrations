package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public
class TimeShifter {
    private static class TimeAdjustData {
        public final Instant sourceTimeStart;
        public final Instant systemTimeStart;

        public TimeAdjustData(Instant sourceTimeStart) {
            this.sourceTimeStart = sourceTimeStart;
            this.systemTimeStart = Instant.now();
        }
    }

    // TODO - Reconsider how time shifting is done
    private final AtomicReference<TimeAdjustData> firstTimestampRef = new AtomicReference<>();

    Instant transformSourceTimeToRealTime(Instant sourceTime) {
        firstTimestampRef.compareAndSet(null, new TimeAdjustData(sourceTime));
        var tad = firstTimestampRef.get();
        // realtime = systemTimeStart + (sourceTime-sourceTimeStart)
        var rval = tad.systemTimeStart
                .plus(Duration.between(firstTimestampRef.get().sourceTimeStart, sourceTime));
        log.trace("Transformed real time=" + sourceTime + " -> " + rval);
        return rval;
    }

    Optional<Instant> transformRealTimeToSourceTime(Instant realTime) {
        return Optional.ofNullable(firstTimestampRef.get())
                .map(tad->{
                    // sourceTime = realTime - systemTimeStart + sourceTimeStart
                    // sourceTime = sourceTimeStart + (realTime-systemTimeStart)
                    var rval = tad.sourceTimeStart
                            .plus(Duration.between(tad.systemTimeStart, realTime));
                    log.trace("Transformed source time=" + realTime + " -> " + rval);
                    return rval;
                });
    }
}
