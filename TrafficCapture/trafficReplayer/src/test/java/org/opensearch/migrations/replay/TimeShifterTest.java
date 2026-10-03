package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class TimeShifterTest {

    public static final int RATE_MULTIPLIER = 8;

    @Test
    public void testShiftsBothWays() {
        TimeShifter shifter = new TimeShifter(RATE_MULTIPLIER);
        var nowTime = Instant.now();
        var sourceTime = nowTime.minus(Duration.ofHours(1));

        Assertions.assertEquals(Optional.empty(), shifter.transformRealTimeToSourceTime(nowTime));
        Assertions.assertThrows(
            Exception.class,
            () -> shifter.transformRealTimeToSourceTime(shifter.transformSourceTimeToRealTime(sourceTime)).get()
        );
        shifter.setFirstTimestamp(sourceTime);
        Assertions.assertEquals(
            sourceTime,
            shifter.transformRealTimeToSourceTime(shifter.transformSourceTimeToRealTime(sourceTime)).get()
        );

        var sourceTime2 = sourceTime.plus(Duration.ofMinutes(RATE_MULTIPLIER));
        Assertions.assertEquals(
            sourceTime2,
            shifter.transformRealTimeToSourceTime(shifter.transformSourceTimeToRealTime(sourceTime2)).get()
        );
    }

    @Test
    void injectedClockProvesPacingWithoutWallClockWaiting() {
        var deploymentStart = Instant.parse("2026-09-26T12:00:00Z");
        var shifter = new TimeShifter(
            2.0,
            Duration.ofSeconds(3),
            java.time.Clock.fixed(deploymentStart, ZoneOffset.UTC)
        );
        var sourceStart = Instant.parse("2025-01-01T00:00:00Z");
        shifter.setFirstTimestamp(sourceStart);

        Assertions.assertEquals(
            deploymentStart.plusSeconds(8),
            shifter.transformSourceTimeToRealTime(sourceStart.plusSeconds(10))
        );
        Assertions.assertEquals(
            sourceStart.plusSeconds(10),
            shifter.transformRealTimeToSourceTime(deploymentStart.plusSeconds(8)).orElseThrow()
        );
    }

    @Test
    void rejectsInvalidRateMultipliers() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TimeShifter(0.0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TimeShifter(Double.NaN));
    }
}
