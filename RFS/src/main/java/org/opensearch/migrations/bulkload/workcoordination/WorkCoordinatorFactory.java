package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Clock;
import java.util.function.Consumer;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.version_es_6_8.OpenSearchWorkCoordinator_ES_6_8;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchWorkCoordinator_OS_2_11;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WorkCoordinatorFactory {
    private final Version version;
    private final String indexNameSuffix;
    private final OpenSearchWorkCoordinator.CompletionRetryConfig completionRetryConfig;

    public WorkCoordinatorFactory(Version version) {
        this(version, "", OpenSearchWorkCoordinator.CompletionRetryConfig.DEFAULT);
    }

    public WorkCoordinatorFactory(Version version, String indexNameSuffix) {
        this(version, indexNameSuffix, OpenSearchWorkCoordinator.CompletionRetryConfig.DEFAULT);
    }

    public WorkCoordinatorFactory(Version version, String indexNameSuffix,
                                  OpenSearchWorkCoordinator.CompletionRetryConfig completionRetryConfig) {
        this.version = version;
        this.indexNameSuffix = indexNameSuffix;
        this.completionRetryConfig = completionRetryConfig;
    }

    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId
        ) {
        return applyConfig(createCoordinator(httpClient, indexNameSuffix, tolerableClientServerClockDifferenceSeconds, workerId));
    }

    public OpenSearchWorkCoordinator get(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId,
            Clock clock,
            Consumer<WorkItemAndDuration> workItemConsumer
        ) {
        return applyConfig(createCoordinator(httpClient, indexNameSuffix, tolerableClientServerClockDifferenceSeconds, workerId, clock, workItemConsumer));
    }

    private OpenSearchWorkCoordinator applyConfig(OpenSearchWorkCoordinator coordinator) {
        coordinator.setCompletionRetryConfig(completionRetryConfig);
        return coordinator;
    }

    private OpenSearchWorkCoordinator createCoordinator(
            AbstractedHttpClient httpClient, String suffix,
            long tolerableClientServerClockDifferenceSeconds, String workerId) {
        if (VersionMatchers.anyOS.or(VersionMatchers.isES_7_X).or(VersionMatchers.isES_8_X).test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient, suffix, tolerableClientServerClockDifferenceSeconds, workerId);
        } else if (VersionMatchers.isES_6_X.or(VersionMatchers.isES_5_X).test(version)) {
            return new OpenSearchWorkCoordinator_ES_6_8(httpClient, suffix, tolerableClientServerClockDifferenceSeconds, workerId);
        }
        throw new IllegalArgumentException("Unsupported version: " + version);
    }

    private OpenSearchWorkCoordinator createCoordinator(
            AbstractedHttpClient httpClient, String suffix,
            long tolerableClientServerClockDifferenceSeconds, String workerId,
            Clock clock, Consumer<WorkItemAndDuration> workItemConsumer) {
        if (VersionMatchers.anyOS.or(VersionMatchers.isES_7_X).or(VersionMatchers.isES_8_X).test(version)) {
            return new OpenSearchWorkCoordinator_OS_2_11(httpClient, suffix, tolerableClientServerClockDifferenceSeconds, workerId, clock, workItemConsumer);
        } else if (VersionMatchers.isES_6_X.or(VersionMatchers.isES_5_X).test(version)) {
            return new OpenSearchWorkCoordinator_ES_6_8(httpClient, suffix, tolerableClientServerClockDifferenceSeconds, workerId, clock, workItemConsumer);
        }
        throw new IllegalArgumentException("Unsupported version: " + version);
    }


    public String getFinalIndexName() {
        return OpenSearchWorkCoordinator.getFinalIndexName(indexNameSuffix);
    }
}
