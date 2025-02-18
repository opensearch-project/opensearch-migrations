package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WorkItemTimeProvider {
    private final AtomicReference<Instant> leaseAcquisitionTimeRef = new AtomicReference<>();
    private final AtomicReference<Instant> documentMigraionStartTimeRef =  new AtomicReference<>();
}
