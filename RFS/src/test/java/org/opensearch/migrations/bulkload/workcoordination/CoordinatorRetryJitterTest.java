package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoodinatorTest.MockHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoodinatorTest.TestResponse;
import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every work-coordination retry loop waits a jittered backoff: at least half the nominal doubling backoff and less
 * than all of it, so workers that fail together don't retry in lockstep. The waits are read from each loop's log line.
 */
class CoordinatorRetryJitterTest {
    private static final Version VERSION = Version.fromString("OS 2.11");
    private static final Pattern BACKING_OFF = Pattern.compile("Backing off (PT[0-9.]+S)");
    private static final Pattern RETRYING_IN = Pattern.compile("\\(Attempt \\d+\\) in (\\d+) ms");

    private static final TestResponse THROTTLED = new TestResponse(429, "THROTTLED", "{\"result\":\"throttled\"}");

    @Test
    void equalJitterStaysBetweenHalfTheDelayAndTheDelay() {
        Assertions.assertEquals(500, RetryJitter.equalJitterMillis(1000, 0.0));
        Assertions.assertEquals(999, RetryJitter.equalJitterMillis(1000, 0.9999));
        Assertions.assertEquals(2, RetryJitter.equalJitterMillis(5, 0.0));
        Assertions.assertEquals(4, RetryJitter.equalJitterMillis(5, 0.9999));
        Assertions.assertEquals(1, RetryJitter.equalJitterMillis(1, 0.5));
        Assertions.assertEquals(0, RetryJitter.equalJitterMillis(0, 0.5));
        for (int i = 0; i < 1000; i++) {
            long jittered = RetryJitter.equalJitterMillis(1000);
            Assertions.assertTrue(jittered >= 500 && jittered < 1000, "got " + jittered);
        }
    }

    @Test
    void retryWithExponentialBackoffJittersEachWait() throws Exception {
        var factory = new WorkCoordinatorFactory(VERSION, "",
            new OpenSearchWorkCoordinator.CompletionRetryConfig(3, 40, 1000));
        try (var coordinator = factory.get(new MockHttpClient(THROTTLED), 2, "worker");
             var logs = new CloseableLogSetup(coordinator.getLoggerName())) {
            Assertions.assertThrows(RuntimeException.class,
                () -> coordinator.completeWorkItem("item__0__0", () -> null));
            assertJittered(List.of(40L, 80L, 160L), waits(logs, BACKING_OFF));
        }
    }

    @Test
    void retryWorkCoordinationUpdateJittersEachWait() throws Exception {
        var factory = new WorkCoordinatorFactory(VERSION);
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("item", 0, 0L).toString();
        try (var coordinator = factory.get(new MockHttpClient(THROTTLED), 2, "worker");
             var logs = new CloseableLogSetup(coordinator.getLoggerName())) {
            Assertions.assertThrows(IOException.class, () -> coordinator.createUnassignedWorkItem(workItem, () -> null));
            var nominal = new ArrayList<Long>();
            for (int i = 0; i < OpenSearchWorkCoordinator.MAX_COORDINATOR_UPDATE_RETRIES; i++) {
                nominal.add(OpenSearchWorkCoordinator.COORDINATOR_UPDATE_RETRY_BASE_MS << i);
            }
            assertJittered(nominal, waits(logs, BACKING_OFF));
        }
    }

    @Test
    void clockDriftRetriesJitterEachWait() throws Exception {
        // An update-by-query that matched an item but changed nothing looks like clock drift to the coordinator.
        var drift = new TestResponse(200, "OK", "{\"total\":1,\"updated\":0,\"noops\":1,\"version_conflicts\":0}");
        try (var coordinator = new WorkCoordinatorFactory(VERSION).get(new MockHttpClient(drift), 2, "worker");
             var logs = new CloseableLogSetup(coordinator.getLoggerName())) {
            Assertions.assertThrows(OpenSearchWorkCoordinator.RetriesExceededException.class,
                () -> coordinator.acquireNextWorkItem(Duration.ofMillis(500),
                    WorkCoordinationTestContext.factory().noOtelTracking()::createAcquireNextItemContext));
            var waits = waits(logs, BACKING_OFF);
            var nominal = new ArrayList<Long>();
            for (int i = 0; i < waits.size(); i++) {
                nominal.add(OpenSearchWorkCoordinator.ACQUIRE_WORK_RETRY_BASE_MS << i);
            }
            Assertions.assertTrue(waits.size() >= 3, "waits: " + waits);
            assertJittered(nominal, waits);
        }
    }

    @Test
    void doUntilJittersEachWait() throws Exception {
        try (var logs = new CloseableLogSetup(OpenSearchWorkCoordinator.log.getName())) {
            var context = WorkCoordinationTestContext.factory().noOtelTracking();
            Assertions.assertThrows(Exception.class,
                () -> OpenSearchWorkCoordinator.doUntil("test", 20, 4,
                    context::createCoordinationInitializationStateContext, () -> "value", v -> v, (v, t) -> false));
            assertJittered(List.of(20L, 40L, 80L), waits(logs, RETRYING_IN));
        }
    }

    static List<Long> waits(CloseableLogSetup logs, Pattern pattern) {
        var waits = new ArrayList<Long>();
        for (var event : logs.getLogEvents()) {
            var matcher = pattern.matcher(event);
            if (matcher.find()) {
                var value = matcher.group(1);
                waits.add(value.startsWith("PT") ? Duration.parse(value).toMillis() : Long.parseLong(value));
            }
        }
        return waits;
    }

    static void assertJittered(List<Long> nominal, List<Long> waits) {
        Assertions.assertEquals(nominal.size(), waits.size(), "waits: " + waits);
        for (int i = 0; i < nominal.size(); i++) {
            long n = nominal.get(i);
            long w = waits.get(i);
            Assertions.assertTrue(w >= n / 2 && w < n,
                "wait " + i + " was " + w + " ms; expected [" + (n / 2) + ", " + n + ") for a nominal " + n + " ms");
        }
    }
}
