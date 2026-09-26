package org.opensearch.migrations.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

// TODO - Reconsider how time shifting is done
@Slf4j
public class TimeShifter {

    private record Baseline(Instant sourceTimeStart, Instant systemTimeStart) {}

    private final AtomicReference<Baseline> baseline = new AtomicReference<>();

    private final double rateMultiplier;
    private final Duration realtimeOffset;
    private final Clock clock;

    public TimeShifter() {
        this(1.0);
    }

    public TimeShifter(double rateMultiplier) {
        this(rateMultiplier, Duration.ZERO);
    }

    public TimeShifter(double rateMultiplier, Duration realtimeOffset) {
        this(rateMultiplier, realtimeOffset, Clock.systemUTC());
    }

    public TimeShifter(double rateMultiplier, Duration realtimeOffset, Clock clock) {
        if (!(rateMultiplier > 0.0) || !Double.isFinite(rateMultiplier)) {
            throw new IllegalArgumentException("rateMultiplier must be finite and positive");
        }
        this.rateMultiplier = rateMultiplier;
        this.realtimeOffset = Objects.requireNonNull(realtimeOffset, "realtimeOffset");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setFirstTimestamp(Instant sourceTime) {
        var didSet = baseline.compareAndSet(
            null,
            new Baseline(sourceTime, clock.instant())
        );
        log.atLevel(didSet ? Level.INFO : Level.TRACE)
            .setMessage("Set baseline source timestamp for all future interactions to {}")
            .addArgument(sourceTime)
            .log();
    }

    Instant transformSourceTimeToRealTime(Instant sourceTime) {
        var established = baseline.get();
        if (established == null) {
            throw new IllegalStateException("setFirstTimestamp has not yet been called");
        }
        // realtime = systemTimeStart + ((sourceTime-sourceTimeStart) / rateMultiplier) + targetOffset
        return established.systemTimeStart()
            .plus(
                Duration.ofMillis(
                    (long) (Duration.between(established.sourceTimeStart(), sourceTime).toMillis() / rateMultiplier)
                )
            )
            .plus(realtimeOffset);
    }

    Optional<Instant> transformRealTimeToSourceTime(Instant realTime) {
        return Optional.ofNullable(baseline.get()).map(established ->
        // sourceTime = sourceTimeStart + (realTime-systemTimeStart-targetOffset) * rateMultiplier
        established.sourceTimeStart().plus(
            Duration.ofMillis(
                (long) (Duration.between(
                    established.systemTimeStart(),
                    realTime.minus(realtimeOffset)
                ).toMillis()
                    * rateMultiplier)
            )
        ));
    }

    public double maxRateMultiplier() {
        return rateMultiplier;
    }
}
