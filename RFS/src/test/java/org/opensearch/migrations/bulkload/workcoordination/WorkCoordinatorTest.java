package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class WorkCoordinatorTest {
    private SearchClusterContainer container;
    private WorkCoordinatorFactory factory;
    
    static Stream<SearchClusterContainer.ContainerVersion> containerVersions() {
        return SupportedClusters.supportedSources(true).stream();
    }

    public static final String DUMMY_FINISHED_DOC_ID = "dummy_finished_doc";

    private Supplier<AbstractedHttpClient> httpClientSupplier;

    @BeforeEach
    void setupHttpClientSupplier() {
        if (container != null) {
            httpClientSupplier = () -> new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                .host(container.getUrl())
                .build()
                .toConnectionContext());
        }
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.close();
            container = null;
        }
        if (httpClientSupplier != null) {
            httpClientSupplier = null;
        }
    }
    
    void setupOpenSearchContainer(SearchClusterContainer.ContainerVersion version) throws Exception {
        // Create a new container with the specified version
        container = new SearchClusterContainer(version);
        factory = new WorkCoordinatorFactory(container.getContainerVersion().getVersion());
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

    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testAcquireLeaseHasNoUnnecessaryConflicts(SearchClusterContainer.ContainerVersion version) throws Exception {
        setupOpenSearchContainer(version);
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

    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testAcquireLeaseForQueryInParallel(SearchClusterContainer.ContainerVersion version) throws Exception {
        // Setup test container and context
        setupOpenSearchContainer(version);
        var testContext = WorkCoordinationTestContext.factory().withAllTracking();
        final int NUM_DOCS = 25;
        final int MAX_RUNS = 2;
        final Duration EXPIRATION = Duration.ofSeconds(10);

        // Make lease acquire calls in parallel across this many requests
        var executor = Executors.newFixedThreadPool(5);

        // Create unassigned work items
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
            List<CompletableFuture<Boolean>> creationFutures =
                    IntStream.range(0, NUM_DOCS)
                            .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                try {
                                    return workCoordinator.createUnassignedWorkItem("R__0__" + i, testContext::createUnassignedWorkContext);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }))
                            .toList();
            CompletableFuture.allOf(creationFutures.toArray(new CompletableFuture[0])).join();
            Assertions.assertTrue(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }

        // Process work items in multiple runs
        for (int run = 0; run < MAX_RUNS; run++) {
            var seenWorkerItems = new ConcurrentHashMap<String, String>();
            List<CompletableFuture<String>> acquisitionFutures = new ArrayList<>();
            boolean markAsComplete = (run == MAX_RUNS - 1);
            Instant runStart = Instant.now();

            for (int i = 0; i < NUM_DOCS; i++) {
                String label = run + "-" + i;
                acquisitionFutures.add(
                        CompletableFuture.supplyAsync(() ->
                                        getWorkItemAndVerify(testContext, label, seenWorkerItems, EXPIRATION, true, markAsComplete),
                                executor
                        )
                );
            }
            // Complete future failing if it takes longer than our expiration
            // If a timeout occurs, the expiration may need to be increased to run on this setup
            CompletableFuture.allOf(acquisitionFutures.toArray(new CompletableFuture[0])).get(
                    EXPIRATION.toMillis(), TimeUnit.MILLISECONDS
            );
            Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size(), "Not all work items were processed");

            // Validate that no further work is available
            try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "NONE")) {
                var nextWorkItem = workCoordinator.acquireNextWorkItem(EXPIRATION, testContext::createAcquireNextItemContext);
                log.atInfo().setMessage("Next work item picked={}").addArgument(nextWorkItem).log();
                Assertions.assertInstanceOf(IWorkCoordinator.NoAvailableWorkToBeDone.class, nextWorkItem);
            } catch (OpenSearchWorkCoordinator.PotentialClockDriftDetectedException e) {
                log.atError().setCause(e)
                        .setMessage("Unexpected clock drift error. Got response: {}")
                        .addArgument(() -> searchForExpiredDocs(e.getTimestampEpochSeconds()))
                        .log();
                throw new AssertionError("Unexpected clock drift error.", e);
            }

            // Check elapsed time does not exceed the expiration duration
            Instant runEnd = Instant.now();
            Duration elapsed = Duration.between(runStart, runEnd);
            Assertions.assertFalse(elapsed.compareTo(EXPIRATION) > 0,
                    String.format("Test run elapsed duration %s exceeded EXPIRATION %s. Increase expiration duration.", elapsed, EXPIRATION));
            log.atInfo().setMessage("Test run duration {} with Expiration {}").addArgument(elapsed).addArgument(EXPIRATION).log();
            // Sleep between runs if needed, to elapse expiration
            if (run < MAX_RUNS - 1) {
                var sleepBetweenRuns = EXPIRATION.plus(Duration.ofSeconds(1));
                log.atInfo().setMessage("Sleeping for {}").addArgument(sleepBetweenRuns).log();
                Thread.sleep(sleepBetweenRuns.toMillis());
            }
        }

        // Final verification: all work items should be complete
        try (var workCoordinator = factory.get(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsNotYetComplete(testContext::createItemsPendingContext));
        }

        var metrics = testContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertNotEquals(0,
                InMemoryInstrumentationBundle.getMetricValueOrZero(metrics, "acquireNextWorkItemRetries"));
    }

    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testAddSuccessorWorkItems(SearchClusterContainer.ContainerVersion version) throws Exception {
        setupOpenSearchContainer(version);
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

    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testAddSuccessorWorkItemsSimultaneous(SearchClusterContainer.ContainerVersion version) throws Exception {
        setupOpenSearchContainer(version);
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

    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testAddSuccessorWorkItemsPartiallyCompleted(SearchClusterContainer.ContainerVersion version) throws Exception {
        setupOpenSearchContainer(version);
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
            var updatePath = workCoordinator.getPathForUpdates(initialWorkItem);
            var response = client.makeJsonRequest("POST", updatePath, null, body);
            Assertions.assertEquals(200, response.getStatusCode(), "Unexpected response " + response.toDiagnosticString());
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


    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testAddSuccessorItemsFailsIfAlreadyDifferentSuccessorItems(SearchClusterContainer.ContainerVersion version) throws Exception {
        setupOpenSearchContainer(version);
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
            var updatePath = workCoordinator.getPathForUpdates(initialWorkItem);
            var response = client.makeJsonRequest("POST", updatePath, null, body);
            Assertions.assertEquals(200, response.getStatusCode(), "Unexpected response " + response.toDiagnosticString());

            // Now attempt to go through with the correct successor item list
            Assertions.assertThrows(IllegalStateException.class,
                    () -> workCoordinator.createSuccessorWorkItemsAndMarkComplete(docId, successorItems, 0,
                            testContext::createSuccessorWorkItemsContext));
        }
    }

    // Create a test where a work item tries to create itself as a successor -- it should fail and NOT be marked as complete. Another worker should pick it up and double the lease time.
    @ParameterizedTest
    @MethodSource("containerVersions")
    public void testCreatingSelfAsSuccessorWorkItemFails(SearchClusterContainer.ContainerVersion version) throws Exception {
        setupOpenSearchContainer(version);
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
