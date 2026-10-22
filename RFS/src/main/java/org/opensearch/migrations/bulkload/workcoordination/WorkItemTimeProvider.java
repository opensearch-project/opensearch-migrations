package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import lombok.Getter;

public class WorkItemTimeProvider {
    @Getter
    private final AtomicReference<Instant> leaseAcquisitionTimeRef = new AtomicReference<>();
    @Getter
    private final AtomicReference<Instant> documentMigraionStartTimeRef =  new AtomicReference<>();
    private final LongSupplier nanoTime;
    private volatile Long acquisitionNanos;
    private final AtomicReference<Long> committedDurationMillis = new AtomicReference<>();

    public WorkItemTimeProvider() {
        this(System::nanoTime);
    }

    WorkItemTimeProvider(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public void recordLeaseAcquisition() {
        acquisitionNanos = nanoTime.getAsLong();
        leaseAcquisitionTimeRef.set(Instant.now());
        committedDurationMillis.set(null);
    }

    /** Freeze the duration so completion retries publish identical metrics. */
    public long freezeDurationMillis() {
        long start = Objects.requireNonNull(acquisitionNanos, "Work-item timer has not started");
        return committedDurationMillis.updateAndGet(duration -> duration != null ? duration
            : Duration.ofNanos(nanoTime.getAsLong() - start).toMillis());
    }
}
