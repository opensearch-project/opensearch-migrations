package com.rfs.cms;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpensearchContainer;

import java.net.URI;
import java.time.Instant;

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
public class TransactionalOpenSearchDataStoreTest {

    final static OpensearchContainer<?> container =
            new OpensearchContainer<>("opensearchproject/opensearch:2.11.0");
    static IWorkCoordinator workCoordinator;

    @BeforeAll
    static void setupOpenSearchContainer() throws Exception {
        // Start the container. This step might take some time...
        container.start();
        workCoordinator = new OpenSearchWorkCoordinator(new ApacheHttpClient(URI.create(container.getHttpHostAddress())),
                2, "testWorker");
    }


    @Test
    void testCreateOrUpdateOrReturnAsIsRequest() throws Exception {
        var objMapper = new ObjectMapper();
        var docId = "A";

        var response1 = workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2);
        Assertions.assertEquals("created", response1.get("result").textValue());
        var doc1 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
            return objMapper.readTree(r.getEntity().getContent());
        });
        Assertions.assertEquals(1, doc1.get("_source").get("numAttempts").longValue());
        var response2 = client.execute(
                workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2),
                r -> {
                    Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                    return objMapper.readTree(r.getEntity().getContent());
                });
        var doc2 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
            return objMapper.readTree(r.getEntity().getContent());
        });
        Assertions.assertEquals("noop", response2.get("result").textValue(),
                "response that came back was unexpected - document == " + objMapper.writeValueAsString(doc2));
        Assertions.assertEquals(1, doc2.get("_source").get("numAttempts").longValue());

        Thread.sleep(2500);

        var response3 = client.execute(
                workCoordinator.makeUpdateRequest(docId, "node_1", Instant.now(), 2),
                r -> {
                    Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                    return objMapper.readTree(r.getEntity().getContent());
                });
        Assertions.assertEquals("updated", response3.get("result").textValue());
        var doc3 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
            return objMapper.readTree(r.getEntity().getContent());
        });
        Assertions.assertEquals(2, doc3.get("_source").get("numAttempts").longValue());
        Assertions.assertTrue(
                doc2.get("_source").get("expiration").longValue() <
                        doc3.get("_source").get("expiration").longValue());

        var response4 = client.execute(
                workCoordinator.makeCompletionRequest(docId, "node_1", Instant.now()), r -> {
                    Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                    return objMapper.readTree(r.getEntity().getContent());
                });
        var doc4 = client.execute(workCoordinator.makeGetRequest(docId), r -> {
            return objMapper.readTree(r.getEntity().getContent());
        });
        Assertions.assertEquals("updated", response4.get("result").textValue());
        Assertions.assertTrue(doc4.get("_source").get("completedAt").longValue() > 0);
        log.info("doc4="+doc4);
    }

    @Test
    public void testAcquireLeaseForQuery() throws Exception {
        var objMapper = new ObjectMapper();
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            for (var i = 0; i<4; ++i) {
                final var docId = "R"+i;
                var addedDocResult= client.execute(workCoordinator.makeCreateDocumentRequest(docId, "node_0"),
                        r-> objMapper.readTree(r.getEntity().getContent()));

//                var response2 = client.execute(
//                        workCoordinator.makeUpdateRequest(docId, "node_1", Instant.E(), 2),
//                        r -> {
//                            Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
//                            return objMapper.readTree(r.getEntity().getContent());
//                        });
//

                log.info("Added doc result: " + addedDocResult);
            }

            var response1 = client.execute(
                    workCoordinator.makeQueryUpdateRequest("node_1", Instant.now(), 2),
                    r -> {
                        //Assertions.assertEquals(HttpStatus.SC_CREATED, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            //Assertions.assertEquals("created", response1.get("result").textValue());
            var doc1 = client.execute(workCoordinator.makeQueryAssignedWorkRequest("node_1"), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            log.info("doc1="+doc1);
            //Assertions.assertEquals(1, doc1.get("_source").get("numAttempts").longValue());
            var response2 = client.execute(
                    workCoordinator.makeQueryUpdateRequest("node_1", Instant.now(), 2),
                    r -> {
                        //Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            var doc2 = client.execute(workCoordinator.makeQueryAssignedWorkRequest("node_1"), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            log.info("doc2="+doc1);
            //Assertions.assertEquals("noop", response2.get("result").textValue(),
            //        "response that came back was unexpected - document == " + objMapper.writeValueAsString(doc2));
            //Assertions.assertEquals(1, doc2.get("_source").get("numAttempts").longValue());
        }
    }
}
