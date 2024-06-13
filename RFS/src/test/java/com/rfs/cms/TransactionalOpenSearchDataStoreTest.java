package com.rfs.cms;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpensearchContainer;

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
    private static final String INDEX_NAME = ".migrations_working_state";

    final static OpensearchContainer<?> container =
            new OpensearchContainer<>("opensearchproject/opensearch:2.11.0");

    @BeforeAll
    static void setupOpenSearchContainer() throws Exception {
        // Start the container. This step might take some time...
        container.start();

        var body = "{\n" +
                "  \"settings\": {\n" +
                "   \"index\": {" +
                "    \"number_of_shards\": 1,\n" +
                "    \"number_of_replicas\": 1\n" +
                "   }\n" +
                "  },\n" +
                "  \"mappings\": {\n" +
                "    \"properties\": {\n" +
                "      \"expiration\": {\n" +
                "        \"type\": \"long\"\n" +
                "      },\n" +
                "      \"completedAt\": {\n" +
                "        \"type\": \"long\"\n" +
                "      },\n" +
                "      \"workerId\": {\n" +
                "        \"type\": \"keyword\",\n" +
                "        \"norms\": false\n" +
                "      },\n" +
                "      \"status\": {\n" +
                "        \"type\": \"keyword\",\n" +
                "        \"norms\": false\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}\n";
        final var httpCreateIndex = new HttpPut(container.getHttpHostAddress() + "/" + INDEX_NAME);
        httpCreateIndex.setHeader("Content-Type", "application/json");
        httpCreateIndex.setHeader("Accept-Encoding", "identity");
        httpCreateIndex.setEntity(new StringEntity(body));

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            var responseBody1 = client.execute(httpCreateIndex, r -> {
                Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                return r.getEntity();
            });
        }
    }

    static String upsertLeaseBodyTemplate = "{\n" +
            "  \"scripted_upsert\": true,\n" +
            "  \"upsert\": {\n" +
            "    \"scriptVersion\": \"{SCRIPT_VERSION}\",\n" +
            "    \"expiration\": 0,\n" +
            "    \"creatorId\": \"{WORKER_ID}\",\n" +
            "    \"numAttempts\": 0\n" +
            //"    \"leaseHolderId\": null\n" +
            "  },\n" +
            "  \"script\": {\n" +
            "    \"lang\": \"painless\",\n" +
            "    \"params\": { \n" +
            "      \"clientTimestamp\": {CLIENT_TIMESTAMP},\n" +
            "      \"expirationWindow\": {EXPIRATION_WINDOW},\n" +
            "      \"workerId\": \"{WORKER_ID}\"\n" +
            "    },\n" +
            "    \"source\": \"" +
            "      if (ctx._source.scriptVersion != \\\"{SCRIPT_VERSION}\\\") {" +
            "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
            "      } " +
            "      long serverTimeSeconds = System.currentTimeMillis() / 1000;" +
            "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {" +
            "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");" +
            "      }" +
            "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);" +
            "      if (params.expirationWindow > 0 && " +                 // don't obtain a lease lock
            "          ctx._source.completedAt == null && " +             // not completed
            "          ctx._source.expiration < serverTimeSeconds && " +  // is expired
            "          ctx._source.expiration < newExpiration) {" +       // sanity check
            "        ctx._source.expiration = newExpiration;" +
            "        ctx._source.leaseHolderId = params.workerId;" +
            "        ctx._source.numAttempts += 1;" +
            "      } else if (params.expirationWindow != 0) {" +
            "        ctx.op = \\\"noop\\\";" +
            "      }" +
            "\"\n" +
            "  }\n" + // close script
            "}"; // close top-level

    static String queryUpdateTemplateGood = "{\n" +
            "\"query\": {\"bool\": { \"must\": [ { \"range\": { \"expiration\": { \"lt\": 1718247000 }}} ] } },\n" +
            "\"script\": {\"source\": \"throw new RuntimeException(\\\"foo\\\");\" }}";

    static String queryUpdateTemplate = "{\n" +
            "\"query\": {" +
            "  \"bool\": {" +
            "    \"must\": [" +
            "      {" +
            "        \"range\": {" +
            "          \"expiration\": { \"lt\": 1718247000 }" +
            "        }" +
            "      }" +
            "    ]," +
            "    \"must_not\": [" +
            "      { \"exists\":" +
            "        { \"field\": \"completedAt\"}" +
            "      }" +
            "    ]" +
            "  }" +
            "}," +
//            "\"refresh\": true,\n" +
            "\"script\": {" +
            "  \"params\": { \n" +
            "    \"clientTimestamp\": {CLIENT_TIMESTAMP},\n" +
            "    \"expirationWindow\": {EXPIRATION_WINDOW},\n" +
            "    \"workerId\": \"{WORKER_ID}\"\n" +
            "  },\n" +
            "  \"source\": \"" +
            "      if (ctx._source.scriptVersion != \\\"{SCRIPT_VERSION}\\\") {" +
            "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
            "      } " +
            "      long serverTimeSeconds = System.currentTimeMillis() / 1000;" +
            "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {" +
            "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");" +
            "      }" +
            "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);" +
            "      if (ctx._source.expiration < serverTimeSeconds && " + // is expired
            "          ctx._source.expiration < newExpiration) {" +      // sanity check
            "        ctx._source.expiration = newExpiration;" +
            "        ctx._source.leaseHolderId = params.workerId;" +
            "        ctx._source.numAttempts += 1;" +
            "      }" +
            "\" " +  // end of source script contents
            "}" +    // end of script block
            "}";

    static String queryWorkersAssignedItemsTemplate = "{\n" +
            "  \"query\": {\n" +
            "    \"term\": { \"leaseHolderId\": \"{WORKER_ID}\"}\n" +
            "  }\n" +
            "}";

    static String markWorkAsCompleteBodyTemplate = "{\n" +
            "  \"script\": {\n" +
            "    \"lang\": \"painless\",\n" +
            "    \"params\": { \n" +
            "      \"clientTimestamp\": {CLIENT_TIMESTAMP},\n" +
            "      \"workerId\": \"{WORKER_ID}\"\n" +
            "    },\n" +
            "    \"source\": \"" +
            "      if (ctx._source.scriptVersion != \\\"{SCRIPT_VERSION}\\\") {" +
            "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
            "      } " +
            "      if (ctx._source.leaseHolderId != params.workerId) {" +
            "        throw new IllegalArgumentException(\\\"work item was owned by \\\" + ctx._source.leaseHolderId + \\\" not \\\" + params.workerId);" +
            "      } else {" +
            "        ctx._source.completedAt = System.currentTimeMillis() / 1000;" +
            "     }" +
            "\"\n" +
            "  }\n" +
            "}";

    String makeAcquireLeasePayload(String workerId, int tolerableClockShiftSeconds,
                                   Instant currentTime, int expirationWindowSeconds) {
        // the notion of 'now' isn't supported with painless scripts
        // https://www.elastic.co/guide/en/elasticsearch/painless/current/painless-datetime.html#_datetime_now
        var body = upsertLeaseBodyTemplate
                .replace("{SCRIPT_VERSION}", "poc")
                .replace("{WORKER_ID}", workerId)
                .replace("{CLIENT_TIMESTAMP}", Long.toString(currentTime.toEpochMilli()/1000))
                .replace("{EXPIRATION_WINDOW}", Integer.toString(expirationWindowSeconds))
                .replace("{CLOCK_DEVIATION_SECONDS_THRESHOLD}", Integer.toString(tolerableClockShiftSeconds));
        log.info("Update body: "+ body);
        return body;
    }

    String createUnassignedWorkDocument(String workerId) {
        return makeAcquireLeasePayload(workerId, 0, Instant.now(), 0);
    }

    String makeCompleteWorkPayload(String workerId, Instant currentTime) {
        var body = markWorkAsCompleteBodyTemplate
                .replace("{SCRIPT_VERSION}", "poc")
                .replace("{WORKER_ID}", workerId)
                .replace("{CLIENT_TIMESTAMP}", Long.toString(currentTime.toEpochMilli()/1000));
        log.info("Mark complete body: "+ body);
        return body;
    }

    @NotNull
    private HttpPost makePostRequest(String documentId, String payload) {
        final HttpPost httpPost = new HttpPost(container.getHttpHostAddress() + "/" + INDEX_NAME + "/_update/" + documentId);

        httpPost.setEntity(new StringEntity(payload));
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept-Encoding", "identity");
        return httpPost;
    }

    private HttpGet makeGetRequest(String documentId) {
        final HttpGet httpGet = new HttpGet(container.getHttpHostAddress() + "/" + INDEX_NAME + "/_doc/" + documentId);
        httpGet.setHeader("Accept-Encoding", "identity");
        return httpGet;
    }

    @NotNull
    private HttpPost makeUpdateRequest(String document, String workerId, Instant currentTime,
                                       int expirationWindowSeconds) {
        return makePostRequest(document, makeAcquireLeasePayload(workerId, 5,
                currentTime, expirationWindowSeconds));
    }

    @NotNull
    private HttpPost makeCreateDocumentRequest(String document, String workerId) {
        return makePostRequest(document, createUnassignedWorkDocument(workerId));
    }

    private HttpPost makeCompletionRequest(String document, String workerId, Instant currentTime) {
        final HttpPost httpPost = new HttpPost(container.getHttpHostAddress() + "/" + INDEX_NAME + "/_update/" + document);

        httpPost.setEntity(new StringEntity(makeCompleteWorkPayload(workerId, currentTime)));
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept-Encoding", "identity");
        return httpPost;
    }

    @Test
    void testCreateOrUpdateOrReturnAsIsRequest() throws Exception {
        var objMapper = new ObjectMapper();
        var docId = "A";
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            var response1 = client.execute(
                    makeUpdateRequest(docId, "node_1", Instant.now(), 2),
                    r -> {
                        Assertions.assertEquals(HttpStatus.SC_CREATED, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            Assertions.assertEquals("created", response1.get("result").textValue());
            var doc1 = client.execute(makeGetRequest(docId), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            Assertions.assertEquals(1, doc1.get("_source").get("numAttempts").longValue());
            var response2 = client.execute(
                    makeUpdateRequest(docId, "node_1", Instant.now(), 2),
                    r -> {
                        Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            var doc2 = client.execute(makeGetRequest(docId), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            Assertions.assertEquals("noop", response2.get("result").textValue(),
                    "response that came back was unexpected - document == " + objMapper.writeValueAsString(doc2));
            Assertions.assertEquals(1, doc2.get("_source").get("numAttempts").longValue());

            Thread.sleep(2500);

            var response3 = client.execute(
                    makeUpdateRequest(docId, "node_1", Instant.now(), 2),
                    r -> {
                        Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            Assertions.assertEquals("updated", response3.get("result").textValue());
            var doc3 = client.execute(makeGetRequest(docId), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            Assertions.assertEquals(2, doc3.get("_source").get("numAttempts").longValue());
            Assertions.assertTrue(
                    doc2.get("_source").get("expiration").longValue() <
                            doc3.get("_source").get("expiration").longValue());

            var response4 = client.execute(
                    makeCompletionRequest(docId, "node_1", Instant.now()), r -> {
                        Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            var doc4 = client.execute(makeGetRequest(docId), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            Assertions.assertEquals("updated", response4.get("result").textValue());
            Assertions.assertTrue(doc4.get("_source").get("completedAt").longValue() > 0);
            log.info("doc4="+doc4);
        }
    }

    private String makeQueryUpdateDocument(String workerId, int tolerableClockShiftSeconds, Instant currentTime,
            int expirationWindowSeconds) {
        return queryUpdateTemplate
                .replace("{SCRIPT_VERSION}", "poc")
                .replace("{WORKER_ID}", workerId)
                .replace("{CLIENT_TIMESTAMP}", Long.toString(currentTime.toEpochMilli()/1000))
                .replace("{EXPIRATION_WINDOW}", Integer.toString(expirationWindowSeconds))
                .replace("{CLOCK_DEVIATION_SECONDS_THRESHOLD}", Integer.toString(tolerableClockShiftSeconds));
    }

    private String makeQueryAssignedWorkDocument(String workerId) {
        return queryWorkersAssignedItemsTemplate.replace("{WORKER_ID}", workerId);
    }

    private HttpGet makeQueryAssignedWorkRequest(String workerId) {
        final var httpGet = new HttpGet(container.getHttpHostAddress() + "/" + INDEX_NAME + "/_search");

        httpGet.setEntity(new StringEntity(makeQueryAssignedWorkDocument(workerId)));
        httpGet.setHeader("Content-Type", "application/json");
        httpGet.setHeader("Accept-Encoding", "identity");
        return httpGet;
    }

    private HttpPost makeQueryUpdateRequest(String workerId, Instant currentTime,
                                            int expirationWindowSeconds) {
        final HttpPost httpPost = new HttpPost(container.getHttpHostAddress() + "/" + INDEX_NAME + "/_update_by_query?refresh=true");

        httpPost.setEntity(new StringEntity(makeQueryUpdateDocument(workerId, 5, currentTime,
                expirationWindowSeconds)));
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept-Encoding", "identity");
        return httpPost;
    }

    @Test
    public void testAcquireLeaseForQuery() throws Exception {
        var objMapper = new ObjectMapper();
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            for (var i = 0; i<4; ++i) {
                var addedDocResult= client.execute(makeCreateDocumentRequest("R"+i, "node_0"),
                        r-> objMapper.readTree(r.getEntity().getContent()));
                log.info("Added doc result: " + addedDocResult);
            }

            var refreshResult = client.execute(new HttpGet(container.getHttpHostAddress() + "/" + INDEX_NAME + "/_refresh"),
                    r -> objMapper.readTree(r.getEntity().getContent()));

            var response1 = client.execute(
                    makeQueryUpdateRequest("node_1", Instant.now(), 2),
                    r -> {
                        //Assertions.assertEquals(HttpStatus.SC_CREATED, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            //Assertions.assertEquals("created", response1.get("result").textValue());
            var doc1 = client.execute(makeQueryAssignedWorkRequest("node_1"), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            log.info("doc1="+doc1);
            //Assertions.assertEquals(1, doc1.get("_source").get("numAttempts").longValue());
            var response2 = client.execute(
                    makeQueryUpdateRequest("node_1", Instant.now(), 2),
                    r -> {
                        //Assertions.assertEquals(HttpStatus.SC_OK, r.getCode());
                        return objMapper.readTree(r.getEntity().getContent());
                    });
            var doc2 = client.execute(makeQueryAssignedWorkRequest("node_1"), r -> {
                return objMapper.readTree(r.getEntity().getContent());
            });
            log.info("doc2="+doc1);
            //Assertions.assertEquals("noop", response2.get("result").textValue(),
            //        "response that came back was unexpected - document == " + objMapper.writeValueAsString(doc2));
            //Assertions.assertEquals(1, doc2.get("_source").get("numAttempts").longValue());
        }
    }
}
