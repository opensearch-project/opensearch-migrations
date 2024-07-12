package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

// TODO - Reconsider how time shifting is done
@Slf4j
public class TimeShifter {

    private final AtomicReference<Instant> sourceTimeStart = new AtomicReference<>();
    private AtomicReference<Instant> systemTimeStart = new AtomicReference<>();

    private final double rateMultiplier;
    private final Duration realtimeOffset;

    public TimeShifter() {
        this(1.0);
    }

    public TimeShifter(double rateMultiplier) {
        this(rateMultiplier, Duration.ZERO);
    }

    public TimeShifter(double rateMultiplier, Duration realtimeOffset) {
        this.rateMultiplier = rateMultiplier;
        this.realtimeOffset = realtimeOffset;
    }

    public void setFirstTimestamp(Instant sourceTime) {
        var didSet = sourceTimeStart.compareAndSet(null, sourceTime);
        if (didSet) {
            var didSetSystemStart = systemTimeStart.compareAndSet(null, Instant.now());
            assert didSetSystemStart : "expected to always start systemTimeStart immediately after sourceTimeStart ";
        }
        log.atLevel(didSet ? Level.INFO : Level.TRACE)
            .setMessage("Set baseline source timestamp for all future interactions to {}")
            .addArgument(sourceTime)
            .log();
    }

    Instant transformSourceTimeToRealTime(Instant sourceTime) {
        if (sourceTimeStart.get() == null) {
            throw new IllegalStateException("setFirstTimestamp has not yet been called");
        }
        // realtime = systemTimeStart + ((sourceTime-sourceTimeStart) / rateMultiplier) + targetOffset
        return systemTimeStart.get()
            .plus(
                Duration.ofMillis(
                    (long) (Duration.between(sourceTimeStart.get(), sourceTime).toMillis() / rateMultiplier)
                )
            )
            .plus(realtimeOffset);
    }

    Optional<Instant> transformRealTimeToSourceTime(Instant realTime) {
        return Optional.ofNullable(sourceTimeStart.get()).map(start ->
        // sourceTime = sourceTimeStart + (realTime-systemTimeStart-targetOffset) * rateMultiplier
        start.plus(
            Duration.ofMillis(
                (long) (Duration.between(systemTimeStart.get(), realTime.minus(realtimeOffset)).toMillis()
                    * rateMultiplier)
            )
        ));
    }

    public double maxRateMultiplier() {
        return rateMultiplier;
    }
}
