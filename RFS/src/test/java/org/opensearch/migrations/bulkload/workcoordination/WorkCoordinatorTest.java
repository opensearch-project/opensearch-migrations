package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The contract here is that the first request in will acquire a lease for the duration that was requested.
 * <p>
 * Once the work is complete, the worker will mark it as such and as long as the workerId matches what was set,
 * the work will be marked for completion and no other lease requests will be granted.
 * <p>
 * When a lease has NOT been acquired, the update request will return a noop.  If it was created,
 * the expiration period will be equal to the original timestamp that the client sent + the expiration window.
 * <p>
 * In case there was an expired lease and this worker has acquired the lease, the result will be 'updated'.
 * The client will need to retrieve the document to find out what the expiration value was.  That means that
 * in all non-contentious cases, clients only need to make one call per work item.  Multiple calls are only
 * required when a lease has expired and a new one is being granted since the worker/client needs to make the
 * GET call to find out the new expiration value.
 */
@Slf4j
@Tag("isolatedTest")
public class WorkCoordinatorTest {
    private static final WorkCoordinatorFactory factory = new WorkCoordinatorFactory(Version.fromString("OS 2.11"));

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
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 2, "testWorker")) {
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
        log.atInfo().setMessage("Searching with... {}").addArgument(body).log();
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

    @Test
    public void testAcquireLeaseHasNoUnnecessaryConflicts() throws Exception {
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var NUM_DOCS = 100;
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R" + i;
                var newWorkItem = IWorkCoordinator.WorkItemAndDuration.WorkItem.valueFromWorkItemString(docId + "__0__0");
                workCoordinator.createUnassignedWorkItem(newWorkItem.toString(), testContext::createUnassignedWorkContext);
            }
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }

        final var seenWorkerItems = new ConcurrentHashMap<String, String>();
        final var expiration = Duration.ofSeconds(60);
        for (int i = 0; i < NUM_DOCS; ++i) {
            var label = "" + i;
            getWorkItemAndVerify(testContext, label, seenWorkerItems, expiration, false, false);
        }
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "finalPass")) {
            var rval = workCoordinator.acquireNextWorkItem(expiration, testContext::createAcquireNextItemContext);
            Assertions.assertInstanceOf(IWorkCoordinator.NoAvailableWorkToBeDone.class, rval);
        }
        var metrics = testContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertEquals(1,
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "noNextWorkAvailableCount"));
        Assertions.assertEquals(0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "acquireNextWorkItemRetries"));
        Assertions.assertEquals(NUM_DOCS,
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "nextWorkAssignedCount"));

        Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size());
    }

    @Test
    @Tag("isolatedTest")
    public void testAcquireLeaseForQuery() throws Exception {
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var NUM_DOCS = 40;
        final var MAX_RUNS = 2;
        var executorService = Executors.newFixedThreadPool(NUM_DOCS);
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R__0__" + i;
                workCoordinator.createUnassignedWorkItem(docId, testContext::createUnassignedWorkContext);
            }
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
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
                var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "NONE")
            ) {
                var nextWorkItem = workCoordinator.acquireNextWorkItem(
                    Duration.ofSeconds(2),
                    testContext::createAcquireNextItemContext
                );
                log.atInfo().setMessage("Next work item picked={}").addArgument(nextWorkItem).log();
                Assertions.assertInstanceOf(IWorkCoordinator.NoAvailableWorkToBeDone.class, nextWorkItem);
            } catch (OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e) {
                log.atError().setCause(e).setMessage("Unexpected clock drift error.  Got response: {}")
                    .addArgument(() -> searchForExpiredDocs(e.getTimestampEpochSeconds()))
                    .log();
            }

            Thread.sleep(expiration.multipliedBy(2).toMillis());
        }
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }
        var metrics = testContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertNotEquals(0,
            InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "acquireNextWorkItemRetries"));
    }

    @Test
    public void testAddSuccessorWorkItems() throws Exception {
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var NUM_DOCS = 20;
        final var NUM_SUCCESSOR_ITEMS = 3;
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R__0__" + i;
                workCoordinator.createUnassignedWorkItem(docId, testContext::createUnassignedWorkContext);
            }
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }

        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "claimItemWorker")) {
            for (var i = 0; i < NUM_DOCS; ++i) {
                String workItemId = getWorkItemAndVerify(
                    testContext,
                    "claimItemWorker",
                    new ConcurrentHashMap<>(),
                    Duration.ofSeconds(10),
                    false,
                    false
                );
                var currentNumPendingItems = workCoordinator.numWorkItemsNotYetComplete(testContext::createItemsPendingContext);

                var successorWorkItems = new ArrayList<String>();
                for (int j = 0; j < NUM_SUCCESSOR_ITEMS; j++) {
                    successorWorkItems.add("successor__" + i + "__" + j);
                }

                workCoordinator.createSuccessorWorkItemsAndMarkComplete(
                    workItemId,
                    successorWorkItems,
                    0,
                    testContext::createSuccessorWorkItemsContext
                );
                Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));

                Assertions.assertEquals(
                    currentNumPendingItems + NUM_SUCCESSOR_ITEMS - 1,
                    workCoordinator.numWorkItemsNotYetComplete(testContext::createItemsPendingContext)
                );
            }
            Assertions.assertEquals(
                NUM_SUCCESSOR_ITEMS * NUM_DOCS,
                workCoordinator.numWorkItemsNotYetComplete(testContext::createItemsPendingContext)
            );
        }

        // Now go claim NUM_DOCS * NUM_SUCCESSOR_ITEMS items to verify all were created and are claimable.
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "claimItemWorker")) {
            for (var i = 0; i < NUM_DOCS * NUM_SUCCESSOR_ITEMS; ++i) {
                getWorkItemAndVerify(
                    testContext,
                    "claimWorker_" + i,
                    new ConcurrentHashMap<>(),
                    Duration.ofSeconds(10),
                    false,
                    true
                );
            }
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }
    }

    @Test
    public void testAddSuccessorWorkItemsSimultaneous() throws Exception {
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final var NUM_DOCS = 20;
        final var NUM_SUCCESSOR_ITEMS = 3;
        var executorService = Executors.newFixedThreadPool(NUM_DOCS);
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R__0__" + i;
                workCoordinator.createUnassignedWorkItem(docId, testContext::createUnassignedWorkContext);
            }
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }

        final var seenWorkerItems = new ConcurrentHashMap<String, String>();
        var allFutures = new ArrayList<CompletableFuture<String>>();
        final var expiration = Duration.ofSeconds(5);
        for (int i = 0; i < NUM_DOCS; ++i) {
            int finalI = i;
            allFutures.add(
                    CompletableFuture.supplyAsync(
                            () -> getWorkItemAndCompleteWithSuccessors(testContext, "successor__0__" + finalI, seenWorkerItems, expiration, true, NUM_SUCCESSOR_ITEMS),
                            executorService
                    )
            );
        }
        CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new)).join();
        Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size());
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "checkResults")) {
            Assertions.assertEquals(NUM_SUCCESSOR_ITEMS * NUM_DOCS, workCoordinator.numWorkItemsNotYetComplete(testContext::createItemsPendingContext));
        }
    }

    @Test
    @Tag("isolatedTest")
    public void testAddSuccessorWorkItemsPartiallyCompleted() throws Exception {
        // A partially completed successor item will have a `successor_items` field and _some_ of the successor work items will be created
        // but not all.  This tests that the coordinator handles this case correctly by continuing to make the originally specific successor items.
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        var docId = "R0";
        var initialWorkItem = docId + "__0__0";
        var N_SUCCESSOR_ITEMS = 3;
        var successorItems = (ArrayList<String>) IntStream.range(1, N_SUCCESSOR_ITEMS + 1).mapToObj(i -> docId + "__0__" + i).collect(Collectors.toList());

        var originalWorkItemExpiration = Duration.ofSeconds(5);
        final var seenWorkerItems = new ConcurrentHashMap<String, String>();

        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "successorTest")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            workCoordinator.createUnassignedWorkItem(initialWorkItem, testContext::createUnassignedWorkContext);
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            // Claim the work item
            getWorkItemAndVerify(testContext, "successorTest", seenWorkerItems, originalWorkItemExpiration, false, false);
            var client = httpClientSupplier.get();
            // Add the list of successors to the work item
            var body = "{\"doc\": {\"successor_items\": \"" + String.join(",", successorItems) + "\"}}";
            var response = client.makeJsonRequest("POST", ".migrations_working_state/_update/" + initialWorkItem, null, body);
            Assertions.assertEquals(200, response.getStatusCode());
            // Create a successor item and then claim it with a long lease.
            workCoordinator.createUnassignedWorkItem(successorItems.get(0), testContext::createUnassignedWorkContext);
            // Now, we should be able to claim the first successor item with a different worker id
            // We should NOT be able to claim the other successor items yet (since they haven't been created yet) or the original item
            String workItemId = getWorkItemAndVerify(testContext, "claimSuccessorItem", seenWorkerItems, Duration.ofSeconds(600), false, true);
            Assertions.assertEquals(successorItems.get(0), workItemId); // We need to ensure that the item we just claimed is the expected one.

            // Sleep for the remainder of the original work item's lease so that it becomes available.
            Thread.sleep(originalWorkItemExpiration.toMillis() + 1000);

            // Okay, we're now in a state where the only document available is the original, incomplete one.
            // We need to make sure that if we try to acquire this work item, it will jump into `createSuccessorWorkItemsAndMarkComplete`,
            // which we can verify because it should be completed successfully and have created the two missing items.
            // After cleaning up the original, acquireNewWorkItem will re-run to claim a valid work item (one of the newly created successor items).
            var nextSuccessorWorkItem = getWorkItemAndVerify(testContext, "cleanupOriginalAndClaimNextSuccessor", seenWorkerItems, originalWorkItemExpiration, false, true);
            Assertions.assertTrue(successorItems.contains(nextSuccessorWorkItem));
            // Now: the original work item is completed, the first successor item is completed (a few lines above) and the second successor is completed (immediately above)
            Assertions.assertEquals(N_SUCCESSOR_ITEMS - 2, workCoordinator.numWorkItemsNotYetComplete(testContext::createItemsPendingContext));

            // Now, we should be able to claim the remaining successor items but the _next_ call should fail because there are no available items
            for (int i = 1; i < (N_SUCCESSOR_ITEMS - 1); i++) {
                workItemId = getWorkItemAndVerify(testContext, "claimItem_" + i, seenWorkerItems, originalWorkItemExpiration, false, true);
                Assertions.assertTrue(successorItems.contains(workItemId));
            }
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            Assertions.assertThrows(NoWorkToBeDoneException.class, () -> {
                getWorkItemAndVerify(testContext, "finalClaimItem", seenWorkerItems, originalWorkItemExpiration, false, false);
            });
            Assertions.assertEquals(N_SUCCESSOR_ITEMS + 1, seenWorkerItems.size());
        }
    }


    @Test
    public void testAddSuccessorItemsFailsIfAlreadyDifferentSuccessorItems() throws Exception {
        // A partially completed successor item will have a `successor_items` field and _some_ of the successor work items will be created
        // but not all.  This tests that the coordinator handles this case correctly by continuing to make the originally specific successor items.
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        var docId = "R0";
        var initialWorkItem = docId + "__0__0";
        var N_SUCCESSOR_ITEMS = 3;
        var successorItems = (ArrayList<String>) IntStream.range(0, N_SUCCESSOR_ITEMS).mapToObj(i -> docId + "_successor_" + i).collect(Collectors.toList());

        var originalWorkItemExpiration = Duration.ofSeconds(5);
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "successorTest")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            workCoordinator.createUnassignedWorkItem(initialWorkItem, testContext::createUnassignedWorkContext);
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            // Claim the work item
            getWorkItemAndVerify(testContext, "successorTest", new ConcurrentHashMap<>(), originalWorkItemExpiration, false, false);
            var client = httpClientSupplier.get();
            // Add an INCORRECT list of successors to the work item
            var incorrectSuccessors = "successor_99,successor_98,successor_97";
            var body = "{\"doc\": {\"successor_items\": \"" + incorrectSuccessors + "\"}}";
            var response = client.makeJsonRequest("POST", ".migrations_working_state/_update/" + initialWorkItem, null, body);
            Assertions.assertEquals(200, response.getStatusCode());

            // Now attempt to go through with the correct successor item list
            Assertions.assertThrows(IllegalStateException.class,
                    () -> workCoordinator.createSuccessorWorkItemsAndMarkComplete(docId, successorItems, 0,
                            testContext::createSuccessorWorkItemsContext));
        }
    }

    // Create a test where a work item tries to create itself as a successor -- it should fail and NOT be marked as complete. Another worker should pick it up and double the lease time.
    @Test
    public void testCreatingSelfAsSuccessorWorkItemFails() throws Exception {
        // A partially completed successor item will have a `successor_items` field and _some_ of the successor work items will be created
        // but not all.  This tests that the coordinator handles this case correctly by continuing to make the originally specific successor items.
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        var initialWorkItem = "R0__0__0";
        var successorItems = new ArrayList<>(List.of("R0__0__0", "R1__0__0", "R2__0__0"));

        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "successorTest")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            workCoordinator.createUnassignedWorkItem(initialWorkItem, testContext::createUnassignedWorkContext);
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            // Claim the work item
            getWorkItemAndVerify(testContext, "successorTest", new ConcurrentHashMap<>(), Duration.ofSeconds(5), false, false);

            // Now attempt to go through with the correct successor item list
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> workCoordinator.createSuccessorWorkItemsAndMarkComplete(initialWorkItem, successorItems,
                            0,
                            testContext::createSuccessorWorkItemsContext));
        }
    }

    @SneakyThrows
    private String getWorkItemAndCompleteWithSuccessors(
            WorkCoordinationTestContext testContext,
            String workerName,
            ConcurrentHashMap<String, String> seenWorkerItems,
            Duration expirationWindow,
            boolean placeFinishedDoc,
            int numSuccessorItems
    ) {
        var workItemId = getWorkItemAndVerify(
                testContext,
                workerName,
                seenWorkerItems,
                expirationWindow,
                placeFinishedDoc,
                false
        );
        ArrayList<String> successorWorkItems = new ArrayList<>();
        for (int j = 0; j < numSuccessorItems; j++) {
            // Replace "__" with "_" in workerId to create a unique name
            successorWorkItems.add(workItemId.replace("__", "_") + "__0__" + j);
        }
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, workerName)) {
            workCoordinator.createSuccessorWorkItemsAndMarkComplete(
                    workItemId, successorWorkItems,
                    0,
                    testContext::createSuccessorWorkItemsContext
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return workItemId;
    }


    class NoWorkToBeDoneException extends Exception {
        public NoWorkToBeDoneException() {
            super();
        }
    }

    static AtomicInteger nonce = new AtomicInteger();

    @SneakyThrows
    private String getWorkItemAndVerify(
        WorkCoordinationTestContext testContext,
        String workerName,
        ConcurrentHashMap<String, String> seenWorkerItems,
        Duration expirationWindow,
        boolean placeFinishedDoc,
        boolean markCompleted
    ) {
        try (
            var workCoordinator = factory.get(
                httpClientSupplier.get(),
                3600, workerName
            )
        ) {
            var doneId = DUMMY_FINISHED_DOC_ID + "__" + nonce.incrementAndGet() + "__0";
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

                    @SneakyThrows
                    @Override
                    public String onNoAvailableWorkToBeDone() throws IOException {
                        throw new NoWorkToBeDoneException();
                    }

                    @Override
                    public String onAcquiredWork(IWorkCoordinator.WorkItemAndDuration workItem) throws IOException,
                        InterruptedException {
                        log.atInfo().setMessage("Next work item picked={}").addArgument(workItem).log();
                        Assertions.assertNotNull(workItem);
                        Assertions.assertNotNull(workItem.getWorkItem().toString());
                        Assertions.assertTrue(workItem.leaseExpirationTime.isAfter(oldNow));
                        var oldVal = seenWorkerItems.put(workItem.getWorkItem().toString(), workItem.getWorkItem().toString());
                        Assertions.assertNull(oldVal);

                        if (markCompleted) {
                            workCoordinator.completeWorkItem(
                                workItem.getWorkItem().toString(),
                                testContext::createCompleteWorkContext
                            );
                        }
                        return workItem.getWorkItem().toString();
                    }
                });
        } catch (OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e) {
            log.atError()
                .setCause(e)
                .setMessage("Unexpected clock drift error.  Got response: {}")
                .addArgument(() -> searchForExpiredDocs(e.getTimestampEpochSeconds()))
                .log();
            throw e;
        }
    }

}
