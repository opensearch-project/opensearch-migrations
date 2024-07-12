package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

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
}
