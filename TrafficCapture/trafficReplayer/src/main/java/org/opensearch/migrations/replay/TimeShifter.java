package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.slf4j.event.Level;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


// TODO - Reconsider how time shifting is done
@Slf4j
public class TimeShifter {

    private final AtomicReference<Instant> sourceTimeStart = new AtomicReference<>();
    private AtomicReference<Instant> systemTimeStart = new AtomicReference<>();

    private final double rateMultiplier;


    public TimeShifter() {
        this(1.0);
    }

    public TimeShifter(double rateMultiplier) {
        this.rateMultiplier = rateMultiplier;
    }

    public void setFirstTimestamp(Instant sourceTime) {
        var didSet = sourceTimeStart.compareAndSet(null, sourceTime);
        if (didSet) {
            var didSetSystemStart = systemTimeStart.compareAndSet(null, Instant.now());
            assert didSetSystemStart : "expected to always start systemTimeStart immediately after sourceTimeStart ";
        }
        log.atLevel(didSet ? Level.INFO : Level.TRACE)
                .setMessage("Set baseline source timestamp for all future interactions to {}")
                .addArgument(sourceTime).log();
    }

    Instant transformSourceTimeToRealTime(Instant sourceTime) {
        // realtime = systemTimeStart + rateMultiplier * (sourceTime-sourceTimeStart)
        if (sourceTimeStart.get() == null) {
            throw new RuntimeException("setFirstTimestamp has not yet been called");
        }
        return systemTimeStart.get()
                .plus(Duration.ofMillis((long)
                        (Duration.between(sourceTimeStart.get(), sourceTime).toMillis() / rateMultiplier)));
    }

    Optional<Instant> transformRealTimeToSourceTime(Instant realTime) {
        return Optional.ofNullable(sourceTimeStart.get())
                .map(start ->
                    // sourceTime = realTime - systemTimeStart + sourceTimeStart
                    // sourceTime = sourceTimeStart + (realTime-systemTimeStart) / rateMultiplier
                    start.plus(Duration.ofMillis((long)
                            (Duration.between(systemTimeStart.get(), realTime).toMillis() * rateMultiplier))));
    }

    public double maxRateMultiplier() {
        return rateMultiplier;
    }
}
