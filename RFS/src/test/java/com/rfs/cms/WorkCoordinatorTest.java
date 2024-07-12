package com.rfs.cms;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.opensearch.testcontainers.OpensearchContainer;

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

    final static OpensearchContainer<?> container = new OpensearchContainer<>("opensearchproject/opensearch:1.3.0");
    public static final String DUMMY_FINISHED_DOC_ID = "dummy_finished_doc";
    private static Supplier<ApacheHttpClient> httpClientSupplier;

    @BeforeAll
    static void setupOpenSearchContainer() throws Exception {
        // Start the container. This step might take some time...
        container.start();
        httpClientSupplier = () -> new ApacheHttpClient(URI.create(container.getHttpHostAddress()));
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 2, "testWorker")) {
            workCoordinator.setup();
        }
    }

    @Test
    void testCreateOrUpdateOrReturnAsIsRequest() throws Exception {
        var objMapper = new ObjectMapper();
        var docId = "A";
        //
        // var response1 = workCoordinator.createOrUpdateLeaseForWorkItem(docId, Duration.ofSeconds(2));
        // Assertions.assertEquals("created", response1.get("result").textValue());
        // var doc1 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // Assertions.assertEquals(1, doc1.get("_source").get("numAttempts").longValue());
        // var response2 = client.execute(
        // workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2),
        // r -> {
        // Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // var doc2 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // Assertions.assertEquals("noop", response2.get("result").textValue(),
        // "response that came back was unexpected - document == " + objMapper.writeValueAsString(doc2));
        // Assertions.assertEquals(1, doc2.get("_source").get("numAttempts").longValue());
        //
        // Thread.sleep(2500);
        //
        // var response3 = client.execute(
        // workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2),
        // r -> {
        // Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // Assertions.assertEquals("updated", response3.get("result").textValue());
        // var doc3 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // Assertions.assertEquals(2, doc3.get("_source").get("numAttempts").longValue());
        // Assertions.assertTrue(
        // doc2.get("_source").get("expiration").longValue() <
        // doc3.get("_source").get("expiration").longValue());
        //
        // var response4 = client.execute(
        // workCoordinator.makeCompletionRequest(docId, "node_1", Instant.now()), r -> {
        // Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // var doc4 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
        // return objMapper.readTree(r.getEntity().getContent());
        // });
        // Assertions.assertEquals("updated", response4.get("result").textValue());
        // Assertions.assertTrue(doc4.get("_source").get("completedAt").longValue() > 0);
        // log.info("doc4="+doc4);
    }

    @SneakyThrows
    private static JsonNode searchForExpiredDocs(long expirationEpochSeconds) {
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
        return objectMapper.readTree(response.getPayloadStream()).path("hits");
    }

    @Test
    public void testAcquireLeaseForQuery() throws Exception {
        var objMapper = new ObjectMapper();
        final var NUM_DOCS = 40;
        final var MAX_RUNS = 2;
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "docCreatorWorker")) {
            Assertions.assertFalse(workCoordinator.workItemsArePending());
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R" + i;
                workCoordinator.createUnassignedWorkItem(docId);
            }
            Assertions.assertTrue(workCoordinator.workItemsArePending());
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
                        () -> getWorkItemAndVerify(label, seenWorkerItems, expiration, markAsComplete)
                    )
                );
            }
            CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new)).join();
            Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size());

            try (
                var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(), 3600, "firstPass_NONE")
            ) {
                var nextWorkItem = workCoordinator.acquireNextWorkItem(Duration.ofSeconds(2));
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
            Assertions.assertFalse(workCoordinator.workItemsArePending());
        }
    }

    static AtomicInteger nonce = new AtomicInteger();

    @SneakyThrows
    private static String getWorkItemAndVerify(
        String workerSuffix,
        ConcurrentHashMap<String, String> seenWorkerItems,
        Duration expirationWindow,
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
            workCoordinator.createOrUpdateLeaseForDocument(doneId, 1);
            workCoordinator.completeWorkItem(doneId);

            return workCoordinator.acquireNextWorkItem(expirationWindow)
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
                        Assertions.assertTrue(workItem.leaseExpirationTime.isAfter(Instant.now()));
                        seenWorkerItems.put(workItem.workItemId, workItem.workItemId);

                        if (markCompleted) {
                            workCoordinator.completeWorkItem(workItem.workItemId);
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
