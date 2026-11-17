package org.opensearch.migrations.bulkload.workcoordination;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkItemTimeProviderTest {
    @Test
    void durationCoversTheAcquiredAttemptAndStaysFrozenDuringCommitRetries() {
        var nanos = new AtomicLong(1_000_000);
        var timing = new WorkItemTimeProvider(nanos::get);
        assertThrows(NullPointerException.class, timing::freezeDurationMillis);
        timing.recordLeaseAcquisition();
        nanos.addAndGet(1_234_999_999);
        assertEquals(1234, timing.freezeDurationMillis());
        nanos.addAndGet(10_000_000_000L);
        assertEquals(1234, timing.freezeDurationMillis());

        timing.recordLeaseAcquisition();
        nanos.addAndGet(5_000_000);
        assertEquals(5, timing.freezeDurationMillis());
    }
}
