package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Clock;
import java.util.function.Consumer;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchWorkCoordinator_OS_2_11;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class WorkCoordinatorFactory {
    private final Version version;

    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId
        ) {
        if (VersionMatchers.isOS_1_X.test(version) || VersionMatchers.isOS_2_X.test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient, tolerableClientServerClockDifferenceSeconds, workerId);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }

    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId,
            Clock clock
        ) {
        if (VersionMatchers.isOS_1_X.test(version) || VersionMatchers.isOS_2_X.test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient, tolerableClientServerClockDifferenceSeconds, workerId, clock);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }
    
    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId,
            Clock clock,
            Consumer<WorkItemAndDuration> workItemConsumer
        ) {
        if (VersionMatchers.isOS_1_X.test(version) || VersionMatchers.isOS_2_X.test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient, tolerableClientServerClockDifferenceSeconds, workerId, clock, workItemConsumer);
        } else {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }
    
}
