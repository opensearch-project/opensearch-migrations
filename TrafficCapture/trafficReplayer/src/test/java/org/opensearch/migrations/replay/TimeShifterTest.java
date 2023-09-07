package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TimeShifterTest {
    @Test
    public void testShiftsBothWays() {
        TimeShifter shifter = new TimeShifter();
        var nowTime = Instant.now();
        var sourceTime = nowTime.minus(Duration.ofHours(1));
        Assertions.assertEquals(Optional.empty(), shifter.transformRealTimeToSourceTime(nowTime));
        Assertions.assertTrue(shifter.transformSourceTimeToRealTime(sourceTime).isBefore(Instant.now()));
        Assertions.assertTrue(shifter.transformSourceTimeToRealTime(sourceTime).
                isAfter(Instant.now().minus(Duration.ofSeconds(1))));
        Assertions.assertEquals(sourceTime, shifter.transformRealTimeToSourceTime(
                shifter.transformSourceTimeToRealTime(sourceTime)).get());
    }
}