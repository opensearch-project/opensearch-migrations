package com.rfs.cms;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.framework.SearchClusterContainer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * The contract here is that the first request in will acquire a lease for the duration that was requested.
 *
 * Once the work is complete, the worker will mark it as such and as long as the workerId matches what was set,
 * the work will be marked for completion and no other lease requests will be granted.
 *
 * When a lease has NOT been acquired, the update request will return a noop.  If it was created,
 * the expiration period will be equal to the original timestamp that the client sent + the expiration window.
 *
 * In case there was an expired lease and this worker has acquired the lease, the result will be 'updated'.
 * The client will need to retrieve the document to find out what the expiration value was.  That means that
 * in all non-contentious cases, clients only need to make one call per work item.  Multiple calls are only
 * required when a lease has expired and a new one is being granted since the worker/client needs to make the
 * GET call to find out the new expiration value.
 */
@Slf4j
public class WorkCoordinatorTest {

    public static final String DUMMY_FINISHED_DOC_ID = "dummy_finished_doc";

    final SearchClusterContainer container = new SearchClusterContainer(SearchClusterContainer.OS_V1_3_16);
    private Supplier<AbstractedHttpClient> httpClientSupplier;

    @BeforeEach
    void setupOpenSearchContainer() throws Exception {
        var testContext = WorkCoordinationTestContext.factory().noOtelTracking();
        // Start the container. This step might take some time...
        container.start();
        httpClientSupplier = () -> new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
            .host(container.getUrl())
            .build()
            .toConnectionContext());
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 2, "testWorker")) {
            workCoordinator.setup(testContext::createCoordinationInitializationStateContext);
        }
    }

    @SneakyThrows
    private JsonNode searchForExpiredDocs(long expirationEpochSeconds) {
        final var body = "{"
            + OpenSearchWorkCoordinator.QUERY_INCOMPLETE_EXPIRED_ITEMS_STR.replace(
                OpenSearchWorkCoordinator.OLD_EXPIRATION_THRESHOLD_TEMPLATE,
                "" + expirationEpochSeconds
            )
            + "}";
        log.atInfo().setMessage(() -> "Searching with... " + body).log();
        var response = httpClientSupplier.get()
            .makeJsonRequest(
                AbstractedHttpClient.GET_METHOD,
                OpenSearchWorkCoordinator.INDEX_NAME + "/_search",
                null,
                body
            );

        var objectMapper = new ObjectMapper();
        return objectMapper.readTree(response.getPayloadBytes()).path("hits");
    }

    private long getMetricValueOrZero(Collection<MetricData> metrics, String s) {
        return metrics.stream()
            .filter(md -> md.getName().startsWith(s))
            .reduce((a, b) -> b)
            .flatMap(md -> md.getLongSumData().getPoints().stream().reduce((a, b) -> b).map(LongPointData::getValue))
            .orElse(0L);
    }

    @Test
    public void testAcquireLeaseHasNoUnnecessaryConflicts() throws Exception {
        log.error("Hello");
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var NUM_DOCS = 100;
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsArePending(testContext::createItemsPendingContext));
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R" + i;
                workCoordinator.createUnassignedWorkItem(docId, testContext::createUnassignedWorkContext);
            }
            Assertions.assertTrue(workCoordinator.workItemsArePending(testContext::createItemsPendingContext));
        }

        final var seenWorkerItems = new ConcurrentHashMap<String, String>();
        final var expiration = Duration.ofSeconds(60);
        for (int i = 0; i < NUM_DOCS; ++i) {
            var label = "" + i;
            getWorkItemAndVerify(testContext, label, seenWorkerItems, expiration, false, false);
        }
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "finalPass")) {
            var rval = workCoordinator.acquireNextWorkItem(expiration, testContext::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.NoAvailableWorkToBeDone.class, rval);
        }
        var metrics = testContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertEquals(1, getMetricValueOrZero(metrics, "noNextWorkAvailableCount"));
        Assertions.assertEquals(0, getMetricValueOrZero(metrics, "acquireNextWorkItemRetries"));
        Assertions.assertEquals(NUM_DOCS, getMetricValueOrZero(metrics, "nextWorkAssignedCount"));

        Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size());
    }

    @Test
    public void testAcquireLeaseForQuery() throws Exception {
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var NUM_DOCS = 40;
        final var MAX_RUNS = 2;
        var executorService = Executors.newFixedThreadPool(NUM_DOCS);
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsArePending(testContext::createItemsPendingContext));
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R" + i;
                workCoordinator.createUnassignedWorkItem(docId, testContext::createUnassignedWorkContext);
            }
            Assertions.assertTrue(workCoordinator.workItemsArePending(testContext::createItemsPendingContext));
        }

        for (int run = 0; run < MAX_RUNS; ++run) {
            final var seenWorkerItems = new ConcurrentHashMap<String, String>();
            var allFutures = new ArrayList<CompletableFuture<String>>();
            final var expiration = Duration.ofSeconds(5);
            var markAsComplete = run + 1 == MAX_RUNS;
            for (int i = 0; i < NUM_DOCS; ++i) {
                var label = run + "-" + i;
                allFutures.add(
                    CompletableFuture.supplyAsync(
                        () -> getWorkItemAndVerify(
                            testContext,
                            label,
                            seenWorkerItems,
                            expiration,
                            true,
                            markAsComplete
                        ),
                        executorService
                    )
                );
            }
            CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new)).join();
            Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size());

            try (
                var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "firstPass_NONE")
            ) {
                var nextWorkItem = workCoordinator.acquireNextWorkItem(
                    Duration.ofSeconds(2),
                    testContext::createAcquireNextItemContext
                );
                log.atInfo().setMessage(() -> "Next work item picked=" + nextWorkItem).log();
                Assertions.assertInstanceOf(IWorkCoordinator.NoAvailableWorkToBeDone.class, nextWorkItem);
            } catch (OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e) {
                log.atError()
                    .setCause(e)
                    .setMessage(
                        () -> "Unexpected clock drift error.  Got response: "
                            + searchForExpiredDocs(e.getTimestampEpochSeconds())
                    )
                    .log();
            }

            Thread.sleep(expiration.multipliedBy(2).toMillis());
        }
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsArePending(testContext::createItemsPendingContext));
        }
        var metrics = testContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertNotEquals(0, getMetricValueOrZero(metrics, "acquireNextWorkItemRetries"));
    }

    static AtomicInteger nonce = new AtomicInteger();

    @SneakyThrows
    private String getWorkItemAndVerify(
        WorkCoordinationTestContext testContext,
        String workerSuffix,
        ConcurrentHashMap<String, String> seenWorkerItems,
        Duration expirationWindow,
        boolean placeFinishedDoc,
        boolean markCompleted
    ) {
        try (
            var workCoordinator = new OpenSearchWorkCoordinator(
                httpClientSupplier.get(),
                3600,
                "firstPass_" + workerSuffix
            )
        ) {
            var doneId = DUMMY_FINISHED_DOC_ID + "_" + nonce.incrementAndGet();
            if (placeFinishedDoc) {
                workCoordinator.createOrUpdateLeaseForDocument(doneId, 1);
                workCoordinator.completeWorkItem(doneId, testContext::createCompleteWorkContext);
            }

            final var oldNow = workCoordinator.getClock().instant(); //
            return workCoordinator.acquireNextWorkItem(expirationWindow, testContext::createAcquireNextItemContext)
                .visit(new IWorkCoordinator.WorkAcquisitionOutcomeVisitor<>() {
                    @Override
                    public String onAlreadyCompleted() throws IOException {
                        throw new IllegalStateException();
                    }

                    @Override
                    public String onNoAvailableWorkToBeDone() throws IOException {
                        throw new IllegalStateException();
                    }

                    @Override
                    public String onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException,
                        InterruptedException {
                        log.atInfo().setMessage(() -> "Next work item picked=" + workItem).log();
                        Assertions.assertNotNull(workItem);
                        Assertions.assertNotNull(workItem.workItemId);
                        Assertions.assertTrue(workItem.leaseExpirationTime.isAfter(oldNow));
                        var oldVal = seenWorkerItems.put(workItem.workItemId, workItem.workItemId);
                        Assertions.assertNull(oldVal);

                        if (markCompleted) {
                            workCoordinator.completeWorkItem(
                                workItem.workItemId,
                                testContext::createCompleteWorkContext
                            );
                        }
                        return workItem.workItemId;
                    }
                });
        } catch (OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e) {
            log.atError()
                .setCause(e)
                .setMessage(
                    () -> "Unexpected clock drift error.  Got response: "
                        + searchForExpiredDocs(e.getTimestampEpochSeconds())
                )
                .log();
            throw e;
        }
    }
}
