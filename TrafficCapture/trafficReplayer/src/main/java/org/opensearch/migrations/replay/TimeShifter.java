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

    private final double rateMultiplier;

    // TODO - Reconsider how time shifting is done
    private final AtomicReference<TimeAdjustData> firstTimestampRef = new AtomicReference<>();

    public TimeShifter() {
        this(1.0);
    }

    public TimeShifter(double rateMultiplier) {
        this.rateMultiplier = rateMultiplier;
    }

    Instant transformSourceTimeToRealTime(Instant sourceTime) {
        firstTimestampRef.compareAndSet(null, new TimeAdjustData(sourceTime));
        var tad = firstTimestampRef.get();
        // realtime = systemTimeStart + rateMultiplier * (sourceTime-sourceTimeStart)
        var rval = tad.systemTimeStart
                .plus(Duration.ofMillis((long)
                        (Duration.between(firstTimestampRef.get().sourceTimeStart, sourceTime).toMillis() / rateMultiplier)));
        log.trace("Transformed real time=" + rval + " <- " + sourceTime);
        return rval;
    }

    Optional<Instant> transformRealTimeToSourceTime(Instant realTime) {
        return Optional.ofNullable(firstTimestampRef.get())
                .map(tad->{
                    // sourceTime = realTime - systemTimeStart + sourceTimeStart
                    // sourceTime = sourceTimeStart + (realTime-systemTimeStart) / rateMultiplier
                    var rval = tad.sourceTimeStart
                            .plus(Duration.ofMillis((long)
                                    (Duration.between(tad.systemTimeStart, realTime).toMillis() * rateMultiplier)));
                    log.trace("Transformed source time=" + rval + " <- " + realTime);
                    return rval;
                });
    }
}
