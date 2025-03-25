package org.opensearch.migrations.testutils;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;


@Execution(ExecutionMode.SAME_THREAD)
class NettyLeakCheckTestExtensionTest {

    String testCase;
    int counter;
    long startTimeNanos;

    @BeforeEach
    public void beforeTest() {
        testCase = null;
        counter = 0;
        startTimeNanos = System.nanoTime();
    }

    @AfterEach
    public void afterTest() {
        // testCase may be null if extension skips beforeEach
        if (testCase == null) {
            return;
        }
        var observedTestDuration = Duration.ofNanos(System.nanoTime()-startTimeNanos);
        switch (testCase) {
            case "testMaxTimeSupercedesReps":
                Assertions.assertTrue(counter < 20, "counter=" + counter);
                Assertions.assertTrue(Duration.ofMillis(100).minus(observedTestDuration).isNegative());
                break;
            case "testMinTimeSupercedesReps":
                Assertions.assertTrue(counter > 1, "counter=" + counter);
                Assertions.assertTrue(Duration.ofMillis(100).minus(observedTestDuration).isNegative());
                break;
            default:
                Assertions.fail("unknown test case: " + testCase);
        }
    }

    @Test
    @WrapWithNettyLeakDetection(maxRuntimeMillis = 100, repetitions = 20)
    public void testMaxTimeSupercedesReps() throws Exception {
        testCase = getMyMethodName();
        ++counter;
        Thread.sleep(10);
    }

    @Test
    @WrapWithNettyLeakDetection(minRuntimeMillis = 100, repetitions = 1)
    public void testMinTimeSupercedesReps() throws Exception {
        testCase = getMyMethodName();
        ++counter;
        Thread.sleep(10);
    }

    private static String getMyMethodName() {
        var backtrace = Thread.currentThread().getStackTrace();
        return backtrace[2].getMethodName();
    }
}
