package com.rfs.cms;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpensearchContainer;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

    final static OpensearchContainer<?> container =
            new OpensearchContainer<>("opensearchproject/opensearch:2.11.0");
    private static Supplier<ApacheHttpClient> httpClientSupplier;


    @BeforeAll
    static void setupOpenSearchContainer() throws Exception {
        // Start the container. This step might take some time...
        container.start();
        httpClientSupplier = () -> new ApacheHttpClient(URI.create(container.getHttpHostAddress()));
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(),
                2, "testWorker")) {
            workCoordinator.setup();
        }
    }
//
//    @BeforeAll
//    static void setupOpenSearchContainer() throws Exception {
//        httpClientSupplier = () -> new ApacheHttpClient(URI.create("http://localhost:58078"));
//        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(),
//                3600, "initializer")) {
//            try (var httpClient = httpClientSupplier.get()) {
//                httpClient.makeJsonRequest("DELETE", OpenSearchWorkCoordinator.INDEX_NAME, null, null);
//            }
//            workCoordinator.setup();
//        }
//    }

    @Test
    void testCreateOrUpdateOrReturnAsIsRequest() throws Exception {
        var objMapper = new ObjectMapper();
        var docId = "A";
//
//        var response1 = workCoordinator.createOrUpdateLeaseForWorkItem(docId, Duration.ofSeconds(2));
//        Assertions.assertEquals("created", response1.get("result").textValue());
//        var doc1 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
//            return objMapper.readTree(r.getEntity().getContent());
//        });
//        Assertions.assertEquals(1, doc1.get("_source").get("numAttempts").longValue());
//        var response2 = client.execute(
//                workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2),
//                r -> {
//                    Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
//                    return objMapper.readTree(r.getEntity().getContent());
//                });
//        var doc2 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
//            return objMapper.readTree(r.getEntity().getContent());
//        });
//        Assertions.assertEquals("noop", response2.get("result").textValue(),
//                "response that came back was unexpected - document == " + objMapper.writeValueAsString(doc2));
//        Assertions.assertEquals(1, doc2.get("_source").get("numAttempts").longValue());
//
//        Thread.sleep(2500);
//
//        var response3 = client.execute(
//                workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2),
//                r -> {
//                    Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
//                    return objMapper.readTree(r.getEntity().getContent());
//                });
//        Assertions.assertEquals("updated", response3.get("result").textValue());
//        var doc3 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
//            return objMapper.readTree(r.getEntity().getContent());
//        });
//        Assertions.assertEquals(2, doc3.get("_source").get("numAttempts").longValue());
//        Assertions.assertTrue(
//                doc2.get("_source").get("expiration").longValue() <
//                        doc3.get("_source").get("expiration").longValue());
//
//        var response4 = client.execute(
//                workCoordinator.makeCompletionRequest(docId, "node_1", Instant.now()), r -> {
//                    Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
//                    return objMapper.readTree(r.getEntity().getContent());
//                });
//        var doc4 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
//            return objMapper.readTree(r.getEntity().getContent());
//        });
//        Assertions.assertEquals("updated", response4.get("result").textValue());
//        Assertions.assertTrue(doc4.get("_source").get("completedAt").longValue() > 0);
//        log.info("doc4="+doc4);
    }

    @Test
    public void testAcquireLeaseForQuery() throws Exception {
        var objMapper = new ObjectMapper();
        final var NUM_DOCS = 40;
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(),
                3600, "docCreatorWorker")) {
            for (var i = 0; i < NUM_DOCS; ++i) {
                final var docId = "R" + i;
                workCoordinator.createUnassignedWorkItem(docId);
            }
        }

        for (int runs=0; runs<2; ++runs) {
            final var seenWorkerItems = new ConcurrentHashMap<String, String>();
            var allThreads = new ArrayList<Thread>();
            final var expiration = Duration.ofSeconds(5);
            for (int i = 0; i < NUM_DOCS; ++i) {
                int finalI = i;
                int finalRuns = runs;
                var t = new Thread(() -> getWorkItemAndVerity(finalRuns + "-" + finalI, seenWorkerItems, expiration));
                allThreads.add(t);
                t.start();
            }
            allThreads.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
            Assertions.assertEquals(NUM_DOCS, seenWorkerItems.size());

            try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(),
                    3600, "firstPass_NONE")) {
                var nextWorkItem = workCoordinator.acquireNextWorkItem(Duration.ofSeconds(2));
                log.error("Next work item picked=" + nextWorkItem);
                Assertions.assertNull(nextWorkItem);
            }

            Thread.sleep(expiration.multipliedBy(2).toMillis());
        }
    }

    @SneakyThrows
    private static void getWorkItemAndVerity(String workerSuffix, ConcurrentHashMap<String, String> seenWorkerItems,
                                             Duration expirationWindow) {
        try (var workCoordinator = new OpenSearchWorkCoordinator(httpClientSupplier.get(),
                3600, "firstPass_"+ workerSuffix)) {
            var nextWorkItem = workCoordinator.acquireNextWorkItem(expirationWindow);
            log.error("Next work item picked=" + nextWorkItem);
            Assertions.assertNotNull(nextWorkItem);
            Assertions.assertNotNull(nextWorkItem.workItemId);
            Assertions.assertTrue(nextWorkItem.leaseExpirationTime.isAfter(Instant.now()));
            seenWorkerItems.put(nextWorkItem.workItemId, nextWorkItem.workItemId);
        }
    }
}
