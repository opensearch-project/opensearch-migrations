package com.rfs.cms;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@Slf4j
public class OpenSearchWorkCoordinator implements IWorkCoordinator {
    private static final String INDEX_NAME = ".migrations_working_state";

    public static final String SCRIPT_VERSION_TEMPLATE = "{SCRIPT_VERSION}";
    public static final String WORKER_ID_TEMPLATE = "{WORKER_ID}";
    public static final String CLIENT_TIMESTAMP_TEMPLATE = "{CLIENT_TIMESTAMP}";
    public static final String EXPIRATION_WINDOW_TEMPLATE = "{EXPIRATION_WINDOW}";
    public static final String CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE = "{CLOCK_DEVIATION_SECONDS_THRESHOLD}";

    public static final String PUT_METHOD = "PUT";
    public static final String POST_METHOD = "POST";
    public static final String RESULT_OPENSSEARCH_KEYWORD = "result";
    public static final String GET_METHOD = "GET";
    public static final String EXPIRATION_FIELD_NAME = "expiration";

    private final long tolerableClientServerClockDifference;
    private final AbstractedHttpClient httpClient;
    private final String workerId;
    private final ObjectMapper objectMapper;

    public OpenSearchWorkCoordinator(AbstractedHttpClient httpClient, long tolerableClientServerClockDifference, String workerId) {
        this.tolerableClientServerClockDifference = tolerableClientServerClockDifference;
        this.httpClient = httpClient;
        this.workerId = workerId;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }

    public void setup() throws IOException {
        var body = "{\n" +
                "  \"settings\": {\n" +
                "   \"index\": {" +
                "    \"number_of_shards\": 1,\n" +
                "    \"number_of_replicas\": 1\n" +
                "   }\n" +
                "  },\n" +
                "  \"mappings\": {\n" +
                "    \"properties\": {\n" +
                "      \"" + EXPIRATION_FIELD_NAME + "\": {\n" +
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

        var response = httpClient.makeJsonRequest(PUT_METHOD, "/" + INDEX_NAME, null, body);
        if (response.getStatusCode() != 200) {
            throw new IOException("Could not setup " + INDEX_NAME + ".  " +
                    "Got error code " + response.getStatusCode() + " and response: " +
                    Arrays.toString(response.getPayloadBytes()));
        }
    }

    enum DocumentModificationResult {
        IGNORED, CREATED, UPDATED;
        static DocumentModificationResult parse(String s) {
            switch (s) {
                case "noop": return DocumentModificationResult.IGNORED;
                case "created": return DocumentModificationResult.CREATED;
                case "updated": return DocumentModificationResult.UPDATED;
                default:
                    throw new IllegalArgumentException("Unknown result " + s);
            }
        }
    }

    DocumentModificationResult createOrUpdateLeaseForDocument(String workItemId, Instant currentTime,
                                                              int expirationWindowSeconds,
                                                              Predicate<DocumentModificationResult> returnValueAdapter)
            throws IOException {
        // the notion of 'now' isn't supported with painless scripts
        // https://www.elastic.co/guide/en/elasticsearch/painless/current/painless-datetime.html#_datetime_now
        final var upsertLeaseBodyTemplate = "{\n" +
                "  \"scripted_upsert\": true,\n" +
                "  \"upsert\": {\n" +
                "    \"scriptVersion\": \"" + SCRIPT_VERSION_TEMPLATE + "\",\n" +
                "    \"" + EXPIRATION_FIELD_NAME + "\": 0,\n" +
                "    \"creatorId\": \"" + WORKER_ID_TEMPLATE + "\",\n" +
                "    \"numAttempts\": 0\n" +
                "  },\n" +
                "  \"script\": {\n" +
                "    \"lang\": \"painless\",\n" +
                "    \"params\": { \n" +
                "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "      \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n" +
                "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n" +
                "    },\n" +
                "    \"source\": \"" +
                "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {" +
                "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
                "      } " +
                "      long serverTimeSeconds = System.currentTimeMillis() / 1000;" +
                "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {" +
                "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");" +
                "      }" +
                "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);" +
                "      if ((params.expirationWindow > 0 && " +                 // don't obtain a lease lock
                "           ctx._source.completedAt == null) {" +              // already done
                "        if (ctx._source.leaseHolderId == params.workerId && " +
                "            ctx._source." + EXPIRATION_FIELD_NAME + " > serverTimeSeconds) {" + // count as an update to force the caller to lookup the expiration time, but no need to modify it
                "          ctx.op = \\\"update\\\";" +
                "        } else if (ctx._source." + EXPIRATION_FIELD_NAME + " < serverTimeSeconds && " + // is expired
                "                   ctx._source." + EXPIRATION_FIELD_NAME + " < newExpiration) {" +      // sanity check
                "          ctx._source." + EXPIRATION_FIELD_NAME + " = newExpiration;" +
                "          ctx._source.leaseHolderId = params.workerId;" +
                "          ctx._source.numAttempts += 1;" +
                "        } else {" +
                "          ctx.op = \\\"noop\\\";" +
                "        }" +
                "      } else if (params.expirationWindow != 0) {" +
                "        ctx.op = \\\"noop\\\";" +
                "      }" +
                "\"\n" +
                "  }\n" + // close script
                "}"; // close top-level

        var body = upsertLeaseBodyTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(currentTime.toEpochMilli()/1000))
                .replace(EXPIRATION_WINDOW_TEMPLATE, Integer.toString(expirationWindowSeconds))
                .replace(CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE, Long.toString(tolerableClientServerClockDifference));

        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/" + workItemId,
                null, body);
        final var resultStr =
                objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        var rval = DocumentModificationResult.parse(resultStr);
        if (!returnValueAdapter.test(rval)) {
            throw Lombok.sneakyThrow(
                    new IllegalStateException("Unexpected response for workItemId: " + workItemId + ".  Response: " +
                            response.toDiagnosticString()));
        }
        return rval;
    }

    DocumentModificationResult createOrUpdateLeaseForDocument(String workItemId, Instant currentTime,
                                                              int expirationWindowSeconds)
        throws IOException {
        return createOrUpdateLeaseForDocument(workItemId, currentTime, expirationWindowSeconds, r -> true);
    }

    public void createUnassignedWorkItem(String workItemId) throws IOException {
        createOrUpdateLeaseForDocument(workItemId, Instant.now(), 0,
                r -> r == DocumentModificationResult.CREATED);
    }

    Instant getExistingExpiration(String workItemId) throws IOException {
        var response = httpClient.makeJsonRequest(GET_METHOD, "/" + INDEX_NAME + "/_doc/" + workItemId,
                null, null);
        return Instant.ofEpochMilli(1000 *
                objectMapper.readTree(response.getPayloadStream()).get(EXPIRATION_FIELD_NAME).longValue());
    }

    @Override
    @NonNull
    public WorkItemAndDuration createOrUpdateLeaseForWorkItem(String workItemId, Duration leaseDuration)
            throws IOException, LeaseNotAcquiredException {
        var startTime = Instant.now();
        var result = createOrUpdateLeaseForDocument(workItemId, Instant.now(), 0);
        switch (result) {
            case CREATED:
                return new WorkItemAndDuration(workItemId, startTime.plus(leaseDuration));
            case UPDATED:
                return new WorkItemAndDuration(workItemId, getExistingExpiration(workItemId));
            case IGNORED:
                throw new LeaseNotAcquiredException();
        }
    }

    <T> T markWorkItemAsComplete(String workItemId, Instant currentTime,
                                 BiFunction<AbstractedHttpClient.AbstractHttpResponse, DocumentModificationResult, T> returnValueAdapter)
            throws IOException {
        final var markWorkAsCompleteBodyTemplate = "{\n" +
                "  \"script\": {\n" +
                "    \"lang\": \"painless\",\n" +
                "    \"params\": { \n" +
                "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n" +
                "    },\n" +
                "    \"source\": \"" +
                "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {" +
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

        var body = markWorkAsCompleteBodyTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(currentTime.toEpochMilli()/1000));

        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/_update/" + workItemId,
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        return returnValueAdapter.apply(response, DocumentModificationResult.parse(resultStr));
    }

    <T> T makeQueryUpdateDocument(String workerId, Instant currentTime, int expirationWindowSeconds,
                                  BiFunction<AbstractedHttpClient.AbstractHttpResponse, DocumentModificationResult, T> returnValueAdapter)
            throws IOException {
        final var queryUpdateTemplate = "{\n" +
                "\"query\": {" +
                "  \"bool\": {" +
                "    \"must\": [" +
                "      {" +
                "        \"range\": {" +
                "          \"" + EXPIRATION_FIELD_NAME + "\": { \"lt\": 1718247000 }" +
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
                "\"script\": {" +
                "  \"params\": { \n" +
                "    \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "    \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n" +
                "    \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n" +
                "  },\n" +
                "  \"source\": \"" +
                "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {" +
                "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
                "      } " +
                "      long serverTimeSeconds = System.currentTimeMillis() / 1000;" +
                "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {" +
                "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");" +
                "      }" +
                "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);" +
                "      if (ctx._source." + EXPIRATION_FIELD_NAME + " < serverTimeSeconds && " + // is expired
                "          ctx._source." + EXPIRATION_FIELD_NAME + " < newExpiration) {" +      // sanity check
                "        ctx._source." + EXPIRATION_FIELD_NAME + " = newExpiration;" +
                "        ctx._source.leaseHolderId = params.workerId;" +
                "        ctx._source.numAttempts += 1;" +
                "      }" +
                "\" " +  // end of source script contents
                "}" +    // end of script block
                "}";

        final var body = queryUpdateTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(currentTime.toEpochMilli()/1000))
                .replace(EXPIRATION_WINDOW_TEMPLATE, Integer.toString(expirationWindowSeconds))
                .replace(CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE, Long.toString(tolerableClientServerClockDifference));

        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/_update_by_query?refresh=true",
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        return returnValueAdapter.apply(response, DocumentModificationResult.parse(resultStr));
    }

    private String makeQueryAssignedWorkDocument(String workerId) throws IOException {
        final var queryWorkersAssignedItemsTemplate = "{\n" +
                "  \"query\": {\n" +
                "    \"term\": { \"leaseHolderId\": \"" + WORKER_ID_TEMPLATE + "\"}\n" +
                "  }\n" +
                "}";
        final var body = queryWorkersAssignedItemsTemplate.replace(WORKER_ID_TEMPLATE, workerId);
        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/_search",
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        return returnValueAdapter.apply(response, DocumentModificationResult.CREATED.parse(resultStr));
    }
}