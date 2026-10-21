package org.opensearch.migrations.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

// TODO - Reconsider how time shifting is done
@Slf4j
public class TimeShifter {

    // The warnings target compiler-generated record members rather than authored methods.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
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

    public TimeShifter(double rateMultiplier, @NonNull Duration realtimeOffset, @NonNull Clock clock) {
        if (rateMultiplier <= 0.0 || !Double.isFinite(rateMultiplier)) {
            throw new IllegalArgumentException("rateMultiplier must be finite and positive");
        }
        this.rateMultiplier = rateMultiplier;
        this.realtimeOffset = realtimeOffset;
        this.clock = clock;
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
