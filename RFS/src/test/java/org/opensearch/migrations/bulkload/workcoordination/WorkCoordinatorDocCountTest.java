package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;
import org.opensearch.migrations.testfixtures.SearchClusterContainer;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("isolatedTest")
class WorkCoordinatorDocCountTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void crashRecoveryUsesThePersistedOutgoingCount(boolean childWasCreated) throws Exception {
        try (var cluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            cluster.start();
            var connection = ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext();
            var context = WorkCoordinationTestContext.factory().noOtelTracking();
            var factory = new WorkCoordinatorFactory(cluster.getContainerVersion().getVersion());
            var client = new CoordinateWorkHttpClient(connection);
            var crash = new AtomicBoolean(true);
            AbstractedHttpClient crashingClient = (method, path, headers, body) -> {
                if (path.contains("_bulk") && crash.compareAndSet(true, false)) {
                    if (childWasCreated) {
                        client.makeRequest(method, path, headers, body);
                    }
                    // Bypass request retries, simulating a process dying during successor creation.
                    throw new AssertionError("simulated crash");
                }
                return client.makeRequest(method, path, headers, body);
            };
            var parent = new WorkItemAndDuration.WorkItem("idx", 0, 0L).toString();
            var child = new WorkItemAndDuration.WorkItem("idx", 0, 149L).toString();
            try (var predecessor = factory.get(crashingClient, 60, "predecessor")) {
                predecessor.setup(context::createCoordinationInitializationStateContext);
                predecessor.createUnassignedWorkItem(parent, context::createUnassignedWorkContext);
                predecessor.acquireNextWorkItem(Duration.ofMinutes(5), context::createAcquireNextItemContext);
                assertThrows(AssertionError.class, () -> predecessor.createSuccessorWorkItemsAndMarkComplete(
                    parent, List.of(child), 0, null, OptionalLong.of(29), context::createSuccessorWorkItemsContext));
                predecessor.releaseWorkItem(parent, context::createReleaseWorkItemContext);
            }
            try (var successor = factory.get(new CoordinateWorkHttpClient(connection), 60, "successor")) {
                var acquired = assertInstanceOf(WorkItemAndDuration.class,
                    successor.acquireNextWorkItem(Duration.ofMinutes(5), context::createAcquireNextItemContext));
                assertEquals(child, acquired.getWorkItem().toString());
                assertEquals(OptionalLong.of(29), acquired.getDocsEmitted(),
                    "the predecessor's incoming total (zero) must not replace its outgoing total");
                successor.releaseWorkItem(child, context::createReleaseWorkItemContext);
                var reacquired = assertInstanceOf(WorkItemAndDuration.class,
                    successor.createOrUpdateLeaseForWorkItem(child, Duration.ofMinutes(5),
                        context::createAcquireSpecificItemContext));
                assertEquals(OptionalLong.of(29), reacquired.getDocsEmitted(),
                    "specific-item acquisition must preserve the count too");
            }
        }
    }
}
